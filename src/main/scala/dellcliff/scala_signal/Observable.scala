package dellcliff.scala_signal

import scala.concurrent.{ExecutionContext, Future}

class Observable[A]
(init: Option[A])
(implicit executor: ExecutionContext) {

  private case class State(observers: Set[A => Any], value: Option[A])

  private var queue = Future(State(Set(), init))
  private val lock = new Object

  def onNext(newValue: A): Future[Unit] = lock.synchronized {
    queue = queue.map { case State(watchers, value) =>
      watchers.foreach(_ (newValue))
      State(watchers, Some(newValue))
    }
    queue.transform({ x => }, x => x)
  }

  def onUpdate(f: Option[A] => Option[A]): Future[Unit] = lock.synchronized {
    queue = queue.map { case State(watchers, value) =>
      val newValue = f(value)
      for {
        v <- newValue
        w <- watchers
      } w(v)
      if (newValue.isDefined)
        State(watchers, newValue)
      else
        State(watchers, value)
    }
    queue.transform({ x => }, x => x)
  }

  def subscribe(f: A => Unit): Future[Unit] = lock.synchronized {
    queue = queue.map { case State(watchers, value) =>
      value.foreach(f)
      State(watchers + f, value)
    }
    queue.transform({ x => }, x => x)
  }
}
