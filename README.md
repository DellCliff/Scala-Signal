# Scala-Signal

Elm-style signals for Scala/Scala.js.
```
// Elm architecture tutorial example 4

import html.Attribute._
import html.Node._
import scala_signal.Address
import scalajs.js.Dynamic
import html.converters.ReactEndpoint

import scala.scalajs.js.JSApp

object Main extends JSApp {

  def main() {
    val ReactDOM = Dynamic.global.ReactDOM
    val document = Dynamic.global.document
    import scalajs.concurrent.JSExecutionContext.Implicits.queue

    sealed trait CounterAction
    case object Increment extends CounterAction
    case object Decrement extends CounterAction

    type CounterModel = Int

    val counterUpdate =
      (action: CounterAction, model: CounterModel) => action match {
        case Increment => model + 1
        case Decrement => model - 1
      }

    val counterStyle =
      'style :=(
        'fontSize := "20px",
        'fontFamily := "monospace",
        'display := "inline-block",
        'width := "50px",
        'textAlign := "center")

    case class CounterContext(actions: Address[CounterAction], remove: Address[Unit])

    val counterViewWithRemoveButton =
      (context: CounterContext, model: CounterModel) =>
        'div (
          'button ('onClick := { () => context.actions(Decrement) }, text"-"),
          'div (counterStyle, text"$model"),
          'button ('onClick := { () => context.actions(Increment) }, text"+"),
          'div (counterStyle),
          'button ('onClick := { () => context.remove(()) }, text"X"))

    type CounterID = Int

    case class CounterListModel(counters: Map[CounterID, CounterModel], nextId: CounterID)

    sealed trait CounterListAction
    case object Insert extends CounterListAction
    case class Remove(id: CounterID) extends CounterListAction
    case class Modify(id: CounterID, action: CounterAction) extends CounterListAction

    val counterListUpdate =
      (action: CounterListAction, model: CounterListModel) =>
        action match {
          case Insert => model.copy(
            nextId = model.nextId + 1,
            counters = model.counters + ((model.nextId, 0)))
          case Remove(id) => model.copy(
            counters = model.counters - id)
          case Modify(id, counterAction) => model.copy(
            counters = model.counters.map {
              case (`id`, counter) => (id, counterUpdate(counterAction, counter))
              case other => other
            })
        }

    val counterListView =
      (address: Address[CounterListAction], model: CounterListModel) =>
        'div (
          'button ('onClick := { () => address(Insert) }, text"Add") ::
            model.counters.map { case (counterId, counterModel) =>
              counterViewWithRemoveButton(
                CounterContext(
                  address.proxy(Modify(counterId, _)),
                  address.proxy({ () => Remove(counterId) })),
                counterModel)
            }.toList: _*)

    for {
      newView <- scala_state.StartApp.start(
        CounterListModel(Map(), 0), counterListUpdate, counterListView)
      reactElement <- ReactEndpoint.makeDom(newView)
    } {
      ReactDOM.render(reactElement, document.body)
    }
  }
}
```
