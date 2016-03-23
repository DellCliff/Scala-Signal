package dellcliff.scala_signal

import scala.concurrent.{ExecutionContext, Future}

sealed trait Address[A] {
  def apply(x: A) = send(x)

  def send(x: A): Future[Unit]

  def proxy[B](f: B => A): Address[B]

  def proxy(f: () => A): Address[Unit] = proxy({ x: Any => f() })
}

sealed trait Signal[A] {
  def dropRepeats(): Signal[A]

  def filter(f: A => Boolean): Signal[A]

  def flatMap[B](f: A => Signal[B]): Signal[B]

  def foldP[B](init: B)(f: (A, B) => B): Signal[B]

  def foreach[U](f: A => U): Unit

  def map[B](f: A => B): Signal[B]

  def sampleOn[B](on: Signal[B]): Signal[A]
}

sealed trait Mailbox[A] {
  val address: Address[A]
  val signal: Signal[A]
}

private class AddressImpl[A]
(signal: SignalImpl[A])
(implicit executor: ExecutionContext) extends Address[A] {
  override def send(x: A): Future[Unit] = signal.observable.onNext(x)

  override def proxy[B](f: (B) => A): Address[B] = {
    val newObservable = new Observable[B](None)
    newObservable.subscribe { x =>
      signal.observable.onNext(f(x))
    }
    new AddressImpl(new SignalImpl(newObservable))
  }
}

object Mailbox {
  def mailbox[A]()(implicit executor: ExecutionContext): Mailbox[A] = {
    val newSignal = new SignalImpl(new Observable[A](None))
    new Mailbox[A] {
      override val address: Address[A] = new AddressImpl(newSignal)
      override val signal: Signal[A] = newSignal
    }
  }

  def mailbox[A]
  (default: A)
  (implicit executor: ExecutionContext): Mailbox[A] = {
    val newSignal = new SignalImpl(new Observable(Some(default)))
    new Mailbox[A] {
      override val address: Address[A] = new AddressImpl(newSignal)
      override val signal: Signal[A] = newSignal
    }
  }
}

object Signal {
  def constant[A](x: A)(implicit executor: ExecutionContext): Signal[A] =
    new SignalImpl(new Observable(Some(x)))

  def merge[A]
  (xs: Signal[A]*)
  (implicit executor: ExecutionContext): Signal[A] = {
    val newObservable = new Observable[A](None)
    for {
      signal <- xs
      value <- signal
    } newObservable.onNext(value)
    new SignalImpl(newObservable)
  }
}

private class SignalImpl[A]
(val observable: Observable[A])
(implicit executor: ExecutionContext) extends Signal[A] {

  override def dropRepeats(): Signal[A] = {
    val newObservable = new Observable[A](None)
    observable.subscribe { currentValue =>
      newObservable.onUpdate {
        case None => Some(currentValue)
        case Some(previousValue) =>
          if (previousValue != currentValue) Some(currentValue)
          else None
      }
    }
    new SignalImpl(newObservable)
  }

  override def flatMap[B](f: (A) => Signal[B]): Signal[B] = {
    val newObservable = new Observable[B](None)
    observable.subscribe { v =>
      f(v).foreach(newObservable.onNext)
    }
    new SignalImpl(newObservable)
  }

  override def filter(f: (A) => Boolean): Signal[A] = {
    val newObservable = new Observable[A](None)
    observable.subscribe { v => if (f(v)) newObservable.onNext(v) }
    new SignalImpl(newObservable)
  }

  override def foldP[B](init: B)(f: (A, B) => B): Signal[B] = {
    val newObservable = new Observable[B](Some(init))
    observable.subscribe { v =>
      newObservable.onUpdate {
        case None => None
        case Some(prev) => Some(f(v, prev))
      }
    }
    new SignalImpl(newObservable)
  }

  override def sampleOn[B](on: Signal[B]): Signal[A] = {
    val newObservable = new Observable[A](None)
    var currentValue: Option[A] = None
    observable.subscribe { v => currentValue = Some(v) }
    on.foreach { b =>
      currentValue.foreach(k => newObservable.onNext(k))
    }
    new SignalImpl(newObservable)
  }

  override def map[B](f: (A) => B): Signal[B] = {
    val newObservable = new Observable[B](None)
    observable.subscribe { v => newObservable.onNext(f(v)) }
    new SignalImpl(newObservable)
  }

  override def foreach[U](f: (A) => U): Unit =
    observable.subscribe { v => f(v) }
}

object StartApp {
  type UpdateFunc[A, M] = (A, M) => M

  type ViewFunc[A, M, V] = (Address[A], M) => V

  type Model[M] = M

  def start[M, V, A]
  (model: Model[M], update: UpdateFunc[A, M], view: ViewFunc[A, M, V])
  (implicit executor: ExecutionContext): Signal[V] = {
    val actions = Mailbox.mailbox[A]()
    actions.signal
      .foldP(model)(update)
      .map(view(actions.address, _))
  }
}
