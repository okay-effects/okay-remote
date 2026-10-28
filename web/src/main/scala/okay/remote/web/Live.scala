package okay.remote.web

import okay.business.scala3.*
import okay.effects.json.{Descent, Json}
import okay.remote.J
import okay.serverless.relay.{Guest, Pairing}
import okay.scala3.std.{Lift, Lifted}
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** WHAT THE PAGE ASKS OF ITS DAEMON, as an effect: the daemon's events (in batches, none lost between screens), an act
 * and its answer, the clock. How the daemon is reached — on the machine, or through the relay — is the handler's */
enum Live[X]:
  case Listen() extends Live[Events[List[(String, Json)]]]
  case Act(body: Json) extends Live[Either[String, Json]]
  case Every(ms: Int) extends Live[Events[Unit]]
  /** the page's address with its token: for another device on the same network */
  case Address() extends Live[String]
  /** this page's pairing given up: the secret forgotten here */
  case Unpair() extends Live[Unit]
  /** the pairing link this page was paired by (to pair another app, the Telegram one): copied, never sent */
  case CopyPairing() extends Live[Boolean]
  /** a pairing link pasted: kept, and the page opened again on it */
  case Pair(link: String) extends Live[Boolean]

/** HOW THE DAEMON IS REACHED: its events into `put`, an act answered */
trait Transport:
  def start(put: ((String, Json)) => Unit): Unit
  def act(body: Json)(done: Either[String, Json] => Unit): Unit
  def address: String = ""
  def forget(): Unit = ()
  def pairing: Option[String] = None

/** ON THE MACHINE: Server-Sent Events in, POST out — the token kept in this browser */
final class OverHttp(token: String) extends Transport:
  def start(put: ((String, Json)) => Unit): Unit =
    val source = new dom.EventSource(s"events?t=$token")
    Live.names.foreach(n => source.addEventListener(n, (e: dom.MessageEvent) =>
      Descent.parse(e.data.asInstanceOf[String]).foreach(j => put(n -> j))))
    source.onopen = _ => put("live" -> Json.Bool(true))
    source.onerror = _ => put("live" -> Json.Bool(false))
  override def address: String = s"${dom.window.location.origin}${dom.window.location.pathname}?t=$token"
  def act(body: Json)(done: Either[String, Json] => Unit): Unit =
    val init = new dom.RequestInit {}
    init.method = dom.HttpMethod.POST
    init.headers = js.Dictionary("Content-Type" -> "application/json")
    init.body = Json.print(body)
    val _ = dom.fetch(s"act?t=$token", init).`then`[Unit](
      (r: dom.Response) => { val _ = r.text().`then`[Unit] { (t: String) =>
        val j = Descent.parse(t).toOption
        done(if r.ok then Right(j.getOrElse(Json.Obj.empty))
          else Left(j.flatMap(J.str(_, "error")).getOrElse(s"${r.status}")))
      } },
      (e: Any) => done(Left(s"the daemon is not reached: $e")))

/**
 * THROUGH THE RELAY (specs/remote.md): okay-serverless's sealed room as a guest — every frame sealed and opened here
 * (WebCrypto), a key derived from the pairing's secret, never sent. The page's acts sealed to the daemon, each answered
 * by its id; the daemon there or not (`live`).
 */
final class OverRelay(paired: Pairing) extends Transport:
  import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
  private var put: ((String, Json)) => Unit = _ => ()
  private var n = 0
  private val asked = scala.collection.mutable.Map.empty[String, Either[String, Json] => Unit]
  private val guest = Guest(dom.window.location.origin, paired, got, present)

  private def got(kind: String, j: Json): Unit = kind match
    case "event" => put(J.str(j, "name").getOrElse("") -> J.get(j, "data").getOrElse(Json.Null))
    case "answer" => J.get(j, "id").map(x => Json.print(x)).flatMap(asked.remove).foreach(done =>
        if J.bool(j, "ok") then done(Right(J.get(j, "body").getOrElse(Json.Obj.empty)))
        else done(Left(J.get(j, "body").flatMap(J.str(_, "error")).getOrElse("refused"))))
    case _ => ()
  private def present(up: Boolean): Unit =
    put("live" -> Json.Bool(up))
    if up then guest.send("hello")
    else { asked.values.foreach(_(Left("the daemon is not reached: try again"))); asked.clear() }

  def start(put: ((String, Json)) => Unit): Unit = { this.put = put; guest.start() }

  def act(body: Json)(done: Either[String, Json] => Unit): Unit =
    if !guest.open then done(Left("the relay is not reached"))
    else
      n += 1
      val id = Json.Str(s"a$n")
      asked(Json.print(id)) = done
      guest.send("act", "id" -> id, "body" -> body)
      val _ = js.timers.setTimeout(90000)(asked.remove(Json.print(id)).foreach(_(Left("no answer in time"))))

  override def pairing: Option[String] = Some(s"${dom.window.location.origin}/#s=${paired.text}")
  override def forget(): Unit =
    dom.window.localStorage.removeItem(Live.RelayKey)
    guest.close()

/** NOT PAIRED: no token here, no secret — the page says how to pair */
object Unpaired extends Transport:
  def start(put: ((String, Json)) => Unit): Unit = put("unpaired" -> Json.Bool(true))
  def act(body: Json)(done: Either[String, Json] => Unit): Unit = done(Left("not paired"))

object Live:
  val names: List[String] = List("hello", "step", "session", "waiting", "policy", "forgot", "telegram")
  val TokenKey = "okay-remote.token"
  val RelayKey = "okay-remote.relay"

  /** THE HANDLER over a transport */
  final class Over(transport: Transport):
    private val inbox = new Inbox
    transport.start(inbox.put)

    def apply[R[_], Rest[_], A](p: A ! R)(using Split[Live, R, Rest], In[Lifted[Future], Rest]): A ! Rest =
      handle(p, Handler.General[Live, Rest, A, A](a => pure(a), [X] => (asked: Live[X], k: X => A ! Rest) =>
        asked match
          case Live.Listen() => k(inbox.events)
          case Live.Every(ms) => k(Events[Unit] { f =>
              val id = js.timers.setInterval(ms.toDouble)(f(()))
              () => js.timers.clearInterval(id)
            })
          case Live.Address() => k(transport.address)
          case Live.Unpair() => transport.forget(); k(())
          case Live.CopyPairing() => transport.pairing match
              case None => k(false)
              case Some(link) =>
                val _ = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard.writeText(link)
                k(true)
          case Live.Pair(link) =>
            val secret = "#s=([A-Za-z0-9_-]{43})".r.findFirstMatchIn(link).map(_.group(1))
            secret.foreach { s =>
              dom.window.localStorage.setItem(RelayKey, s)
              dom.window.location.replace(dom.window.location.pathname)
            }
            k(secret.nonEmpty)
          case Live.Act(body) =>
            val answered = Promise[Either[String, Json]]()
            transport.act(body)(r => { val _ = answered.trySuccess(r) })
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
