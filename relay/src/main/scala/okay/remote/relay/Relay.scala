package okay.remote.relay

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * THE RELAY (specs/remote.md, "Anywhere: the relay"): a Cloudflare Worker. It gives the page (the same page as the
 * daemon's), and joins a daemon and its phones in a room — a Durable Object per room, nothing in its storage. It
 * lets in only who has the room's auth (its SHA-256 is the room), and passes frames it cannot read: they are sealed
 * end to end by a key it never has.
 */
object Front:
  private var files: js.Dictionary[String] = js.Dictionary()
  @JSExportTopLevel("setFiles") def setFiles(f: js.Dictionary[String]): Unit = files = f

  private val kinds = Map("html" -> "text/html; charset=utf-8", "js" -> "text/javascript; charset=utf-8",
    "webmanifest" -> "application/manifest+json", "svg" -> "image/svg+xml")
  val secure: Map[String, String] = Map("x-content-type-options" -> "nosniff", "referrer-policy" -> "no-referrer",
    "x-frame-options" -> "DENY", "strict-transport-security" -> "max-age=31536000",
    "content-security-policy" -> ("default-src 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; " +
      "img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))

  def response(status: Int, body: String, kind: String, headers: Map[String, String] = secure): js.Dynamic =
    js.Dynamic.newInstance(g.Response)(body, js.Dynamic.literal(status = status,
      headers = (headers + ("content-type" -> kind) + ("cache-control" -> "no-cache")).toJSDictionary))

  @JSExportTopLevel("route")
  def route(request: js.Dynamic, env: js.Dynamic): js.Promise[js.Dynamic] =
    val url = js.Dynamic.newInstance(g.URL)(request.url)
    val path = url.pathname.asInstanceOf[String]
    val method = request.method.asInstanceOf[String]
    (path.split("/", -1).toList.drop(1) match
      case List("r", room, role) if room.matches("[0-9a-f]{64}") && (role == "daemon" || role == "phone") =>
        val ns = env.ROOMS
        ns.get(ns.idFromName(room)).fetch(request).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      case _ if method != "GET" && method != "HEAD" => Future.successful(response(405, "GET only", "text/plain"))
      case _ =>
        val name = if path == "/" then "index.html" else path.drop(1)
        Future.successful(files.get(name) match
          case Some(text) if !name.contains("/") => response(200, text,
              kinds.getOrElse(name.reverse.takeWhile(_ != '.').reverse, "text/plain"))
          case _ => response(404, "no such page", "text/plain"))
    ).toJSPromise

/** A ROOM: one daemon (the newest), any number of phones; each socket's role in its attachment, which outlives the
 * object's sleep (hibernation). A frame from a phone goes to the daemon; from the daemon, to every phone. The phones
 * told, in plain words, whether the daemon is there (`online` / `offline`) — nothing else is ever read here */
@JSExportTopLevel("Room")
class Room(state: js.Dynamic, env: js.Dynamic) extends js.Object:
  private val pair = js.Dynamic.newInstance(g.WebSocketRequestResponsePair)("ping", "pong")
  state.setWebSocketAutoResponse(pair)
  private val most = 2800000

  private def open: List[js.Dynamic] =
    state.getWebSockets().asInstanceOf[js.Array[js.Dynamic]].toList.filter(_.readyState.asInstanceOf[Int] == 1)
  private def role(ws: js.Dynamic): String =
    scala.util.Try(ws.deserializeAttachment().asInstanceOf[js.UndefOr[String]].toOption.getOrElse("")).getOrElse("")
  private def daemons = open.filter(role(_) == "daemon")
  private def phones = open.filter(role(_) == "phone")
  private def send(ws: js.Dynamic, text: String): Unit = scala.util.Try(ws.send(text))

  def fetch(request: js.Dynamic): js.Promise[js.Dynamic] =
    val url = js.Dynamic.newInstance(g.URL)(request.url)
    val parts = url.pathname.asInstanceOf[String].split("/", -1).toList.drop(1)
    val (room, who) = (parts.lift(1).getOrElse(""), parts.lift(2).getOrElse(""))
    val auth = Option(url.searchParams.get("auth").asInstanceOf[String]).getOrElse("")
    val upgrade = Option(request.headers.get("upgrade").asInstanceOf[String]).getOrElse("")
    (if upgrade.toLowerCase != "websocket" then Future.successful(Front.response(426, "a WebSocket", "text/plain"))
    else Room.sha256(auth).map { h =>
      if auth.isEmpty || !Room.same(h, room) then Front.response(403, "not this room's", "text/plain")
      else
        val pair = js.Dynamic.newInstance(g.WebSocketPair)()
        val (client, server) = (pair.selectDynamic("0"), pair.selectDynamic("1"))
        val older = if who == "daemon" then daemons else Nil
        state.acceptWebSocket(server)
        server.serializeAttachment(who)
        if who == "daemon" then
          older.foreach(o => scala.util.Try(o.close(4000, "another daemon took this room")))
          phones.foreach(send(_, "online"))
        else send(server, if daemons.nonEmpty then "online" else "offline")
        js.Dynamic.newInstance(g.Response)(null, js.Dynamic.literal(status = 101, webSocket = client))
    }).toJSPromise

  def webSocketMessage(ws: js.Dynamic, message: js.Any): Unit =
    if js.typeOf(message) == "string" then
      val text = message.asInstanceOf[String]
      if text.length > most then scala.util.Try(ws.close(1009, "at most 2.8 MB"))
      else role(ws) match
        case "phone" =>
          val to = daemons
          if to.isEmpty then send(ws, "offline") else to.foreach(send(_, text))
        case "daemon" => phones.foreach(send(_, text))
        case _ => ()

  def webSocketClose(ws: js.Dynamic, code: Int, reason: String, wasClean: Boolean): Unit = gone(ws)
  def webSocketError(ws: js.Dynamic, error: js.Any): Unit = gone(ws)
  private def gone(ws: js.Dynamic): Unit =
    val was = role(ws)
    scala.util.Try(ws.close(1000, "closed"))
    if was == "daemon" && daemons.forall(_ eq ws) then phones.foreach(send(_, "offline"))

object Room:
  def sha256(s: String): Future[String] =
    val data = js.Dynamic.newInstance(g.TextEncoder)().encode(s)
    g.crypto.subtle.digest("SHA-256", data).asInstanceOf[js.Promise[js.typedarray.ArrayBuffer]].toFuture.map { b =>
      new js.typedarray.Uint8Array(b).toArray.map(x => f"${x.toInt & 255}%02x").mkString
    }
  def same(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0
