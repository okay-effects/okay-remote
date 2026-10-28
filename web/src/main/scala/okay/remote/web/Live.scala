package okay.remote.web

import okay.business.scala3.*
import okay.effects.json.{Descent, Json}
import okay.remote.J
import okay.scala3.std.{Lift, Lifted}
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** WHAT THE PAGE ASKS OF ITS DAEMON, as an effect: the daemon's events (in batches, none lost between screens), an act
 * and its answer, the clock. Where the daemon is, and with which token, is the handler's */
enum Live[X]:
  case Listen() extends Live[Events[List[(String, Json)]]]
  case Act(body: Json) extends Live[Either[String, Json]]
  case Every(ms: Int) extends Live[Events[Unit]]
  /** the page's address with its token: for another device */
  case Address() extends Live[String]

object Live:
  val names: List[String] = List("hello", "step", "session", "waiting", "policy", "forgot")

  /** THE HANDLER over HTTP: Server-Sent Events in, POST out — the token kept in this browser (it came once in the
   * address, `?t=`, and is taken out of it) */
  final class Over(token: String):
    private val inbox = new Inbox
    private val source = new dom.EventSource(s"events?t=$token")
    names.foreach(n => source.addEventListener(n, (e: dom.MessageEvent) =>
      Descent.parse(e.data.asInstanceOf[String]).foreach(j => inbox.put(n -> j))))
    source.onopen = _ => inbox.put("live" -> Json.Bool(true))
    source.onerror = _ => inbox.put("live" -> Json.Bool(false))

    def apply[R[_], Rest[_], A](p: A ! R)(using Split[Live, R, Rest], In[Lifted[Future], Rest]): A ! Rest =
      handle(p, Handler.General[Live, Rest, A, A](a => pure(a), [X] => (asked: Live[X], k: X => A ! Rest) =>
        asked match
          case Live.Listen() => k(inbox.events)
          case Live.Every(ms) => k(Events[Unit] { f =>
              val id = js.timers.setInterval(ms.toDouble)(f(()))
              () => js.timers.clearInterval(id)
            })
          case Live.Address() =>
            k(s"${dom.window.location.origin}${dom.window.location.pathname}?t=$token")
          case Live.Act(body) =>
            val answered = Promise[Either[String, Json]]()
            val init = new dom.RequestInit {}
            init.method = dom.HttpMethod.POST
            init.headers = js.Dictionary("Content-Type" -> "application/json")
            init.body = Json.print(body)
            val _ = dom.fetch(s"act?t=$token", init).`then`[Unit](
              (r: dom.Response) => { val _ = r.text().`then`[Unit] { (t: String) =>
                val j = Descent.parse(t).toOption
                val _ = answered.success(if r.ok then Right(j.getOrElse(Json.Obj.empty))
                  else Left(j.flatMap(J.str(_, "error")).getOrElse(s"${r.status}")))
              } },
              (e: Any) => { val _ = answered.success(Left(s"the daemon is not reached: $e")) })
            Lift.lift[Future, Either[String, Json], Rest](answered.future).flatMap(k)))

  /** WHAT CAME, KEPT UNTIL A SCREEN LISTENS: all there is to the one listening, on the browser's next turn */
  final class Inbox:
    private var queue = Vector.empty[(String, Json)]
    private var listener: Option[List[(String, Json)] => Unit] = None
    private var scheduled = false
    def put(e: (String, Json)): Unit = { queue :+= e; later() }
    private def later(): Unit =
      if !scheduled && listener.nonEmpty && queue.nonEmpty then
        scheduled = true
        js.timers.setTimeout(0) {
          scheduled = false
          listener.foreach { l =>
            if queue.nonEmpty then
              listener = None
              val batch = queue.toList
              queue = Vector.empty
              l(batch)
          }
        }
    val events: Events[List[(String, Json)]] = Events { k =>
      listener = Some(k)
      later()
      () => if listener.exists(_ eq k) then listener = None
    }
