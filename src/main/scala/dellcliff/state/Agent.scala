package dellcliff.state

import scala.concurrent.{ExecutionContext, Future}

class Agent[A](init: A) {
  private var value = init
  private var watchers = List[(Future[Unit], ExecutionContext, A => Unit)]()
  private val watchersLock = new Object
  private var queue: Option[Future[Unit]] = None
  private val queueLock = new Object

  def reset(newValue: A)(implicit executor: ExecutionContext): Unit =
    swap(x => newValue)(executor)

  def swap(f: A => A)(implicit executor: ExecutionContext): Unit = queueLock.synchronized {
    val work: Unit => Unit = { (x: Unit) =>
      val newValue = f(value)
      watchersLock.synchronized {
        value = newValue
        watchers = watchers.map { case (future, context, func) =>
          (future.map(x => func(newValue))(context), context, func)
        }
      }
    }
    queue = Some((queue match {
      case None => Future()(executor)
      case Some(future) => future
    }).map(work)(executor))
  }

  def watch(f: A => Unit)(implicit executor: ExecutionContext): Unit = watchersLock.synchronized {
    val currentValue = value
    watchers = (Future(f(currentValue))(executor), executor, f) :: watchers
  }
}

object Agent {
  def apply[A](init: A) = new Agent(init)
}
