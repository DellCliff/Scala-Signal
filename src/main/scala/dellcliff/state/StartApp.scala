package dellcliff.state

import scala.concurrent.ExecutionContext

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
