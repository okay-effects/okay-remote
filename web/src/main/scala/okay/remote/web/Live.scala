package okay.remote.web

import okay.business.scala3.*
import okay.effects.json.{Descent, Json}
import okay.remote.{Envelope, J, Pairing}
import okay.scala3.std.{Lift, Lifted}
import org.scalajs.dom
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

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
 * THROUGH THE RELAY (specs/remote.md): a WebSocket to the room the pairing's secret names; every frame sealed and
 * opened here by WebCrypto (AES-256-GCM, a key derived from the secret, never sent); a frame not from the daemon,
 * out of time or seen before, dropped. The relay's plain words: `online` / `offline` — whether the daemon is there.
 */
final class OverRelay(secret: Uint8Array) extends Transport:
  private val subtle = g.crypto.subtle
  private val seen = Envelope.Seen()
  private var ws: Option[dom.WebSocket] = None
  private var key: js.Dynamic = null
  private var put: ((String, Json)) => Unit = _ => ()
  private var n = 0
  private val asked = scala.collection.mutable.Map.empty[String, Either[String, Json] => Unit]
  private var backoff = 1000

  private def bytes(s: String): Uint8Array = js.Dynamic.newInstance(g.TextEncoder)().encode(s).asInstanceOf[Uint8Array]
  private def concat(a: Uint8Array, b: Uint8Array): Uint8Array =
    val c = new Uint8Array(a.length + b.length); c.set(a); c.set(b, a.length); c
  private def sha(data: Uint8Array): js.Promise[ArrayBuffer] =
    subtle.digest("SHA-256", data).asInstanceOf[js.Promise[ArrayBuffer]]
  private def hex(b: ArrayBuffer): String = new Uint8Array(b).toArray.map(x => f"${x.toInt & 255}%02x").mkString
  private def nonce: String =
    val b = new Uint8Array(12); g.crypto.getRandomValues(b); b.toArray.map(x => f"${x.toInt & 255}%02x").mkString

  def start(put: ((String, Json)) => Unit): Unit =
    this.put = put
    import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
    for
      enc <- sha(concat(bytes(Pairing.encLabel), secret)).toFuture
      k <- subtle.importKey("raw", enc, "AES-GCM", false, js.Array("encrypt", "decrypt"))
        .asInstanceOf[js.Promise[js.Dynamic]].toFuture
      auth <- sha(concat(bytes(Pairing.authLabel), secret)).toFuture.map(hex)
      room <- sha(bytes(auth)).toFuture.map(hex)
    do
      key = k
      dial(room, auth)

  private def dial(room: String, auth: String): Unit =
    val scheme = if dom.window.location.protocol == "https:" then "wss" else "ws"
    val s = new dom.WebSocket(s"$scheme://${dom.window.location.host}/r/$room/phone?auth=$auth")
    ws = Some(s)
    s.onopen = _ => backoff = 1000
    s.onmessage = (e: dom.MessageEvent) => e.data match
      case "online" => put("live" -> Json.Bool(true)); send("hello")
      case "offline" => put("live" -> Json.Bool(false))
      case "pong" => ()
      case frame: String => opened(frame)
      case _ => ()
    s.onclose = (_: dom.CloseEvent) =>
      if ws.contains(s) then
        put("live" -> Json.Bool(false))
        asked.values.foreach(_(Left("the relay was lost: try again")))
        asked.clear()
        val _ = js.timers.setTimeout(backoff.toDouble)(dial(room, auth))
        backoff = math.min(backoff * 2, 30000)
    val beat = js.timers.setInterval(30000)(if s.readyState == 1 then s.send("ping"))
    s.addEventListener("close", (_: dom.Event) => js.timers.clearInterval(beat))

  private def opened(frame: String): Unit =
    val raw = js.Dynamic.global.atob(frame.replace('-', '+').replace('_', '/') +
      "=" * ((4 - frame.length % 4) % 4)).asInstanceOf[String]
    val b = new Uint8Array(raw.length)
    raw.indices.foreach(i => b(i) = raw.charAt(i).toShort)
    val iv = b.subarray(0, 12)
    val params = js.Dynamic.literal(name = "AES-GCM", iv = iv, additionalData = bytes(Pairing.aad))
    val _ = subtle.decrypt(params, key, b.subarray(12)).asInstanceOf[js.Promise[ArrayBuffer]].`then`[Unit](
      (plain: ArrayBuffer) =>
        val text = js.Dynamic.newInstance(g.TextDecoder)().decode(plain).asInstanceOf[String]
        Envelope.open(text, "daemon", js.Date.now().toLong, seen).foreach {
          case ("event", j) => put(J.str(j, "name").getOrElse("") -> J.get(j, "data").getOrElse(Json.Null))
          case ("answer", j) => J.get(j, "id").map(x => Json.print(x)).flatMap(asked.remove).foreach(done =>
              if J.bool(j, "ok") then done(Right(J.get(j, "body").getOrElse(Json.Obj.empty)))
              else done(Left(J.get(j, "body").flatMap(J.str(_, "error")).getOrElse("refused"))))
          case _ => ()
        },
      (_: Any) => ())

  private def send(kind: String, fields: (String, Json)*): Unit = ws.filter(_.readyState == 1).foreach { s =>
    val text = Envelope.wrap("phone", js.Date.now().toLong, nonce, kind, fields*)
    val iv = new Uint8Array(12); g.crypto.getRandomValues(iv)
    val params = js.Dynamic.literal(name = "AES-GCM", iv = iv, additionalData = bytes(Pairing.aad))
    val _ = subtle.encrypt(params, key, bytes(text)).asInstanceOf[js.Promise[ArrayBuffer]].`then`[Unit] {
      (box: ArrayBuffer) =>
        val all = concat(iv, new Uint8Array(box))
        val bin = all.toArray.map(x => (x.toInt & 255).toChar).mkString
        val b64 = js.Dynamic.global.btoa(bin).asInstanceOf[String]
        s.send(b64.replace('+', '-').replace('/', '_').reverse.dropWhile(_ == '=').reverse)
    }
  }

  def act(body: Json)(done: Either[String, Json] => Unit): Unit =
    if !ws.exists(_.readyState == 1) || key == null then done(Left("the relay is not reached"))
    else
      n += 1
      val id = Json.Str(s"a$n")
      asked(Json.print(id)) = done
      send("act", "id" -> id, "body" -> body)
      val _ = js.timers.setTimeout(90000)(asked.remove(Json.print(id)).foreach(_(Left("no answer in time"))))

  override def pairing: Option[String] =
    Option(dom.window.localStorage.getItem(Live.RelayKey)).map(s => s"${dom.window.location.origin}/#s=$s")
  override def forget(): Unit =
    dom.window.localStorage.removeItem(Live.RelayKey)
    ws.foreach(_.close())
    ws = None

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
