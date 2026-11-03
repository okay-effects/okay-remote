package okay.remote.web

import okay.effects.json.{Descent, Json}
import okay.remote.J
import okay.remote.page.{Live, Transport}
import okay.serverless.relay.{Guest, Pairing}
import org.scalajs.dom
import scala.scalajs.js

/** WHAT A BROWSER KEEPS AND COPIES, for every transport of a page: a pasted link's secret kept, the page opened again
 * on it; a text on the clipboard */
trait InTheBrowser extends Transport:
  override def pair(link: String): Boolean = Live.secretOf(link) match
    case None => false
    case Some(s) =>
      dom.window.localStorage.setItem(Web.RelayKey, s)
      dom.window.location.replace(dom.window.location.pathname)
      true
  override def copy(text: String): Boolean =
    val _ = dom.window.navigator.asInstanceOf[js.Dynamic].clipboard.writeText(text)
    true

/** ON THE MACHINE: Server-Sent Events in, POST out — the token kept in this browser */
final class OverHttp(token: String) extends InTheBrowser:
  def start(put: ((String, Json)) => Unit): Unit =
    val source = new dom.EventSource(s"events?t=$token")
    Web.names.foreach(n => source.addEventListener(n, (e: dom.MessageEvent) =>
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
final class OverRelay(paired: Pairing) extends InTheBrowser:
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
    dom.window.localStorage.removeItem(Web.RelayKey)
    guest.close()

/** NOT PAIRED: no token here, no secret — the page says how to pair */
object Unpaired extends InTheBrowser:
  def start(put: ((String, Json)) => Unit): Unit = put("unpaired" -> Json.Bool(true))
  def act(body: Json)(done: Either[String, Json] => Unit): Unit = done(Left("not paired"))

object Web:
  /** the daemon's events a page listens for */
  val names: List[String] = List("hello", "step", "session", "waiting", "policy", "forgot", "telegram", "typing")
  val TokenKey = "okay-remote.token"
  val RelayKey = "okay-remote.relay"
