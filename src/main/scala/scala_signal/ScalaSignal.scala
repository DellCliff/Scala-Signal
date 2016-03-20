package scala_signal

import scala.concurrent.{ExecutionContext, Future}

sealed trait Signal[A] {
  def dropRepeats(): Signal[A]

  def filter(f: A => Boolean): Signal[A]

  def flatMap[B](f: A => Signal[B]): Signal[B]

  def foldP[B](init: B)(f: (A, B) => B): Signal[B]

  def foreach[U](f: A => U): Unit = this.map(f)

  def map[B](f: A => B): Signal[B]

  def sampleOn[B](on: Signal[B]): Signal[A]
}

object Signal {
  def constant[A](x: A)(implicit executor: ExecutionContext): Signal[A] =
    Mailbox.mailbox(x).signal

  def merge[A]
  (xs: Signal[A]*)
  (implicit executor: ExecutionContext): Signal[A] = {
    val newSignal = new SignalImpl[A](new Observable(None))
    for (x <- xs)
      x match {
        case signal: SignalImpl[A] =>
          signal.observable.addObserver(newSignal.observable.onNextSync)
        case other =>
      }
    newSignal
  }
}

private class SignalImpl[A]
(val observable: Observable[A])
(implicit executor: ExecutionContext) extends Signal[A] {
  override def dropRepeats(): Signal[A] = {
    val newSignal = new SignalImpl[A](new Observable(None))
    val lock = new Object
    var previousValue: Option[A] = None
    observable.addObserver({ x =>
      lock.synchronized {
        if (!previousValue.contains(x)) {
          previousValue = Some(x)
          newSignal.observable.onNextSync(x)
        }
      }
    })
    newSignal
  }

  override def flatMap[B](f: (A) => Signal[B]): Signal[B] = {
    val newSignal = new SignalImpl[B](new Observable(None))
    observable.addObserver({ x =>
      f(x) match {
        case signal: SignalImpl[B] =>
          signal.observable.addObserver({ y =>
            newSignal.observable.onNextSync(y)
          })
        case other =>
      }
    })
    newSignal
  }

  override def filter(f: (A) => Boolean): Signal[A] = {
    val newSignal = new SignalImpl[A](new Observable(None))
    observable.addObserver({ x => if (f(x)) newSignal.observable.onNextSync(x) })
    newSignal
  }

  override def foldP[B](seed: B)(f: (A, B) => B): Signal[B] = {
    val newSignal = new SignalImpl[B](new Observable(Some(seed)))
    var state = seed
    val lock = new Object
    observable.addObserver({ x =>
      lock.synchronized {
        state = f(x, state)
        newSignal.observable.onNextSync(state)
      }
    })
    newSignal
  }

  override def map[B](f: (A) => B): Signal[B] = {
    val newSignal = new SignalImpl[B](new Observable(None))
    observable.addObserver({ x => newSignal.observable.onNextSync(f(x)) })
    newSignal
  }

  override def sampleOn[B](on: Signal[B]): Signal[A] = {
    val newSignal = new SignalImpl[A](new Observable(None))
    on match {
      case signal: SignalImpl[B] =>
        signal.observable.addObserver({ y =>
          for (x <- observable.state())
            newSignal.observable.onNextSync(x)
        })
      case other =>
    }
    newSignal
  }
}

sealed trait Address[A] {
  def apply(x: A) = send(x)

  def send(x: A): Future[Unit]

  def proxy[B](f: B => A): Address[B]

  def proxy(f: () => A): Address[Unit] = proxy({ x: Any => f() })
}

private class AddressImpl[A]
(signal: SignalImpl[A])
(implicit executor: ExecutionContext) extends Address[A] {
  override def send(x: A): Future[Unit] =
    signal.observable.onNext(x)

  override def proxy[B](f: B => A): Address[B] = {
    val newSignal = new SignalImpl[B](new Observable(None))
    newSignal.observable.addObserver({ x =>
      signal.observable.onNext(f(x))
    })
    new AddressImpl(newSignal)
  }
}

sealed trait Mailbox[A] {
  val address: Address[A]
  val signal: Signal[A]
}

object Mailbox {
  def mailbox[A]()(implicit executor: ExecutionContext): Mailbox[A] = {
    val newSignal = new SignalImpl[A](new Observable(None))
    new Mailbox[A] {
      val address = new AddressImpl(newSignal)
      val signal = newSignal
    }
  }

  def mailbox[A]
  (default: A)
  (implicit executor: ExecutionContext): Mailbox[A] = {
    val newSignal = new SignalImpl(new Observable(Some(default)))
    new Mailbox[A] {
      val address = new AddressImpl(newSignal)
      val signal = newSignal
    }
  }
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

private class Observable[A]
(init: Option[A])
(implicit executor: ExecutionContext) {
  private val lock = new Object
  private var observers: Set[A => Any] = Set()
  private var value: Option[A] = init

  def addObserver(f: A => Any): Unit = lock.synchronized {
    observers = observers ++ Set((x: A) => Future(f(x)))
    for (x <- value) Future(f(x))
  }

  def onNextSync(x: A): Unit = lock.synchronized {
    value = Some(x)
    for (f <- observers) Future(f(x))
  }

  def onNext(x: A): Future[Unit] =
    Future(onNextSync(x))

  def state(): Option[A] = value
}
