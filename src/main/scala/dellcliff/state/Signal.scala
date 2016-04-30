package dellcliff.state

import scala.concurrent.ExecutionContext

trait Address[A] {
  def apply(x: A)(implicit executor: ExecutionContext) = send(x)(executor)

  def send(x: A)(implicit executor: ExecutionContext): Unit

  def proxy[B](f: B => A)(implicit executor: ExecutionContext): Address[B]
}

trait Signal[A] {
  def dropRepeats()(implicit executor: ExecutionContext): Signal[A]

  def filter(f: A => Boolean)(implicit executor: ExecutionContext): Signal[A]

  def flatMap[B](f: A => Signal[B])(implicit executor: ExecutionContext): Signal[B]

  def foldP[B](init: B)(f: (A, B) => B)(implicit executor: ExecutionContext): Signal[B]

  def foreach[U](f: A => U)(implicit executor: ExecutionContext): Unit

  def map[B](f: A => B)(implicit executor: ExecutionContext): Signal[B]

  def sampleOn[B](on: Signal[B])(implicit executor: ExecutionContext): Signal[A]
}

trait Mailbox[A] {
  val address: Address[A]
  val signal: Signal[A]
}

private class SignalImpl[A](val agent: Agent[Option[A]]) extends Signal[A] {
  override def dropRepeats()(implicit executor: ExecutionContext): Signal[A] = {
    val newAgent = Agent[Option[A]](None)
    var lw: Option[A] = None
    agent.watch {
      case None =>
      case Some(x) => lw = lw match {
        case Some(`x`) => Some(x)
        case Some(oldX) =>
          val newX = Some(x)
          newAgent.reset(newX)(executor)
          newX
        case None =>
          val newX = Some(x)
          newAgent.reset(newX)(executor)
          newX
      }
    }(executor)
    new SignalImpl(newAgent)
  }

  override def flatMap[B](f: (A) => Signal[B])(implicit executor: ExecutionContext): Signal[B] = {
    val newAgent = Agent[Option[B]](None)
    agent.watch {
      case None =>
      case Some(x) => f(x).foreach(y => newAgent.reset(Some(y))(executor))
    }(executor)
    new SignalImpl(newAgent)
  }

  override def filter(f: (A) => Boolean)(implicit executor: ExecutionContext): Signal[A] = {
    val newAgent = Agent[Option[A]](None)
    agent.watch {
      case None =>
      case Some(x) => if (f(x)) newAgent.reset(Some(x))(executor)
    }(executor)
    new SignalImpl(newAgent)
  }

  override def foldP[B](init: B)(f: (A, B) => B)(implicit executor: ExecutionContext): Signal[B] = {
    val newAgent = Agent[Option[B]](Some(init))
    agent.watch {
      case None =>
      case Some(a) => newAgent.swap {
        case None => None
        case Some(b) => Some(f(a, b))
      }(executor)
    }(executor)
    new SignalImpl(newAgent)
  }

  override def sampleOn[B](on: Signal[B])(implicit executor: ExecutionContext): Signal[A] = {
    val newAgent = Agent[Option[A]](None)
    var currentValue: Option[A] = None
    agent.watch {
      case None =>
      case x: Some[A] => currentValue = x
    }(executor)
    on.foreach(b => currentValue match {
      case None =>
      case x: Some[A] => newAgent.reset(x)(executor)
    })(executor)
    new SignalImpl(newAgent)
  }

  override def foreach[U](f: (A) => U)(implicit executor: ExecutionContext): Unit = {
    agent.watch {
      case None =>
      case Some(x) => f(x)
    }(executor)
  }

  override def map[B](f: (A) => B)(implicit executor: ExecutionContext): Signal[B] = {
    val newAgent = Agent[Option[B]](None)
    agent.watch {
      case None =>
      case Some(x) => newAgent.reset(Some(f(x)))(executor)
    }(executor)
    new SignalImpl(newAgent)
  }
}

private class AddressImpl[A](val signal: SignalImpl[A]) extends Address[A] {
  override def send(x: A)(implicit executor: ExecutionContext): Unit =
    signal.agent.reset(Some(x))(executor)

  override def proxy[B](f: (B) => A)(implicit executor: ExecutionContext): Address[B] = {
    val newAgent = Agent[Option[B]](None)
    newAgent.watch {
      case None =>
      case Some(b) => signal.agent.reset(Some(f(b)))
    }(executor)
    new AddressImpl(new SignalImpl(newAgent))
  }
}

object Mailbox {
  def mailbox[A](): Mailbox[A] = {
    val newSignal = new SignalImpl(Agent[Option[A]](None))
    new Mailbox[A] {
      override val address: Address[A] = new AddressImpl(newSignal)
      override val signal: Signal[A] = newSignal
    }
  }

  def mailbox[A](default: A): Mailbox[A] = {
    val newSignal = new SignalImpl[A](Agent(Some(default)))
    new Mailbox[A] {
      override val address: Address[A] = new AddressImpl(newSignal)
      override val signal: Signal[A] = newSignal
    }
  }
}

object Signal {
  def constant[A](x: A): Signal[A] = new SignalImpl(Agent(Some(x)))

  def merge[A](xs: Signal[A]*)(implicit executor: ExecutionContext): Signal[A] = {
    val newAgent = Agent[Option[A]](None)
    xs.foreach(s => s.foreach(v => newAgent.reset(Some(v))(executor)))
    new SignalImpl(newAgent)
  }
}
