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
      case List("tg") if method == "POST" => Telegram.webhook(request, env)
      case List("tg", "info") => Future.successful(
          if Telegram.on(env) then response(200, s"""{"bot":"${env.TG_BOT}"}""", "application/json")
          else response(404, "no bot here", "text/plain"))
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
 * told, in plain words, whether the daemon is there (`online` / `offline`) — nothing else is ever read here, but the
 * daemon's control lines (`ctl:`, specs/remote.md "Telegram"), which never go to a phone */
@JSExportTopLevel("Room")
class Room(state: js.Dynamic, env: js.Dynamic) extends js.Object:
  private val pair = js.Dynamic.newInstance(g.WebSocketRequestResponsePair)("ping", "pong")
  state.setWebSocketAutoResponse(pair)
  private val most = 2800000

  private def open: List[js.Dynamic] =
    state.getWebSockets().asInstanceOf[js.Array[js.Dynamic]].toList.filter(_.readyState.asInstanceOf[Int] == 1)
  /** a socket's attachment: its role, and for the daemon its room, the page's origin, the bound chat */
  private def att(ws: js.Dynamic): js.Dynamic =
    scala.util.Try(ws.deserializeAttachment()).toOption.filter(a => !js.isUndefined(a) && a != null)
      .getOrElse(js.Dynamic.literal())
  private def field(ws: js.Dynamic, k: String): String =
    att(ws).selectDynamic(k).asInstanceOf[js.UndefOr[String]].toOption.filter(_ != null).getOrElse("")
  private def role(ws: js.Dynamic): String = field(ws, "role")
  private def daemons = open.filter(role(_) == "daemon")
  private def phones = open.filter(role(_) == "phone")
  private def send(ws: js.Dynamic, text: String): Unit = scala.util.Try(ws.send(text))
  private def keep(ws: js.Dynamic, k: String, v: String): Unit =
    val a = att(ws); a.updateDynamic(k)(v); ws.serializeAttachment(a)

  def fetch(request: js.Dynamic): js.Promise[js.Dynamic] =
    val url = js.Dynamic.newInstance(g.URL)(request.url)
    val parts = url.pathname.asInstanceOf[String].split("/", -1).toList.drop(1)
    if parts == List("bind") then bind(Option(url.searchParams.get("chat").asInstanceOf[String]).getOrElse(""))
    else
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
          server.serializeAttachment(js.Dynamic.literal(role = who, room = room,
            origin = url.origin.asInstanceOf[String], chat = ""))
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
          if to.isEmpty then send(ws, "offline") else if !text.startsWith("ctl:") then to.foreach(send(_, text))
        case "daemon" => if text.startsWith("ctl:") then control(ws, text.drop(4)) else phones.foreach(send(_, text))
        case _ => ()

  // ---- THE DAEMON'S CONTROL LINES: Telegram (specs/remote.md)

  private def control(ws: js.Dynamic, line: String): Unit =
    val (word, rest) = line.span(_ != ' ')
    val arg = rest.trim
    word match
      case "link" if Telegram.on(env) && arg.matches("[A-Za-z0-9_-]{16,48}") =>
        val links = env.LINKS
        links.get(links.idFromName(arg)).fetch(s"https://link/set?room=${field(ws, "room")}")
      case "chat" if Telegram.on(env) =>
        val (chat, sig) = arg.span(_ != ' ')
        Telegram.sign(env, s"${field(ws, "room")}:$chat").foreach(want =>
          if chat.nonEmpty && Room.same(want, sig.trim) then keep(ws, "chat", chat))
      case "unchat" => keep(ws, "chat", "")
      case "notify" if Telegram.on(env) =>
        val chat = field(ws, "chat")
        if chat.nonEmpty then Telegram.tell(env, chat, scala.util.Try(js.JSON.parse(arg).text.asInstanceOf[String])
          .getOrElse("okay-remote"), field(ws, "origin")).foreach(ok => if !ok then
            keep(ws, "chat", ""); send(ws, "ctl:unbound"))
      case _ => ()

  /** a chat that asked (`/start <code>`), bound to this room's daemon: told it, with the relay's signature */
  private def bind(chat: String): js.Promise[js.Dynamic] =
    daemons.lastOption match
      case None => Future.successful(Front.response(409, "no daemon", "text/plain")).toJSPromise
      case Some(d) => Telegram.sign(env, s"${field(d, "room")}:$chat").map { sig =>
          keep(d, "chat", chat)
          send(d, s"ctl:bound $chat $sig")
          Front.response(200, "bound", "text/plain")
        }.toJSPromise

  def webSocketClose(ws: js.Dynamic, code: Int, reason: String, wasClean: Boolean): Unit = gone(ws)
  def webSocketError(ws: js.Dynamic, error: js.Any): Unit = gone(ws)
  private def gone(ws: js.Dynamic): Unit =
    val was = role(ws)
    scala.util.Try(ws.close(1000, "closed"))
    if was == "daemon" && daemons.forall(_ eq ws) then phones.foreach(send(_, "offline"))

/** A LINKING CODE: which room asked for it — in memory, ten minutes, taken once */
@JSExportTopLevel("Link")
class Link(state: js.Dynamic, env: js.Dynamic) extends js.Object:
  private var room: Option[(String, Double)] = None
  def fetch(request: js.Dynamic): js.Promise[js.Dynamic] =
    val url = js.Dynamic.newInstance(g.URL)(request.url)
    val now = js.Date.now()
    val answer = url.pathname.asInstanceOf[String] match
      case "/set" =>
        val r = Option(url.searchParams.get("room").asInstanceOf[String]).getOrElse("")
        if room.isEmpty && r.matches("[0-9a-f]{64}") then room = Some((r, now))
        Front.response(200, "", "text/plain")
      case "/take" =>
        val r = room.filter(x => now - x._2 < 600000).map(_._1).getOrElse("")
        room = None
        Front.response(if r.isEmpty then 404 else 200, r, "text/plain")
      case _ => Front.response(404, "", "text/plain")
    js.Promise.resolve[js.Dynamic](answer)

object Room:
  def sha256(s: String): Future[String] =
    val data = js.Dynamic.newInstance(g.TextEncoder)().encode(s)
    g.crypto.subtle.digest("SHA-256", data).asInstanceOf[js.Promise[js.typedarray.ArrayBuffer]].toFuture.map { b =>
      new js.typedarray.Uint8Array(b).toArray.map(x => f"${x.toInt & 255}%02x").mkString
    }
  def same(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0

/** THE BOT (specs/remote.md, "Telegram"): its webhook, its words — only that something waits, never what */
object Telegram:
  def on(env: js.Dynamic): Boolean = !js.isUndefined(env.TG_TOKEN) && !js.isUndefined(env.TG_SECRET) &&
    !js.isUndefined(env.TG_BOT)
  private def api(env: js.Dynamic, method: String): String =
    val base = if js.isUndefined(env.TG_API) then "https://api.telegram.org" else env.TG_API.asInstanceOf[String]
    s"$base/bot${env.TG_TOKEN}/$method"

  def sign(env: js.Dynamic, text: String): Future[String] =
    val enc = js.Dynamic.newInstance(g.TextEncoder)()
    val subtle = g.crypto.subtle
    for
      key <- subtle.importKey("raw", enc.encode(env.TG_SECRET), js.Dynamic.literal(name = "HMAC", hash = "SHA-256"),
        false, js.Array("sign")).asInstanceOf[js.Promise[js.Dynamic]].toFuture
      mac <- subtle.sign("HMAC", key, enc.encode(text)).asInstanceOf[js.Promise[js.typedarray.ArrayBuffer]].toFuture
    yield new js.typedarray.Uint8Array(mac).toArray.map(x => f"${x.toInt & 255}%02x").mkString

  /** a message to a chat, with the page's button; false if the chat cannot be told (it blocked the bot) */
  def tell(env: js.Dynamic, chat: String, text: String, origin: String): Future[Boolean] =
    val button = if origin.startsWith("https://") then js.Array(js.Array(js.Dynamic.literal(text = "Open okay-remote",
      web_app = js.Dynamic.literal(url = origin + "/")))) else js.Array()
    val body = js.JSON.stringify(js.Dynamic.literal(chat_id = chat, text = text,
      reply_markup = js.Dynamic.literal(inline_keyboard = button)))
    g.fetch(api(env, "sendMessage"), js.Dynamic.literal(method = "POST",
      headers = js.Dynamic.literal(`content-type` = "application/json"), body = body))
      .asInstanceOf[js.Promise[js.Dynamic]].toFuture
      .map(r => r.status.asInstanceOf[Int] != 403).recover { case _ => true }

  /** an update from Telegram: `/start <code>` binds the chat to the room that made the code */
  def webhook(request: js.Dynamic, env: js.Dynamic): Future[js.Dynamic] =
    val secret = Option(request.headers.get("x-telegram-bot-api-secret-token").asInstanceOf[String]).getOrElse("")
    if !on(env) || !Room.same(secret, env.TG_SECRET.asInstanceOf[String]) then
      Future.successful(Front.response(403, "not Telegram", "text/plain"))
    else request.json().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { u =>
      val msg = u.message
      val text = scala.util.Try(msg.text.asInstanceOf[String]).toOption.filter(_ != null).getOrElse("")
      val chat = scala.util.Try(msg.chat.id.toString).toOption.getOrElse("")
      val origin = js.Dynamic.newInstance(g.URL)(request.url).origin.asInstanceOf[String]
      val privately = scala.util.Try(msg.chat.`type`.asInstanceOf[String]).toOption.contains("private")
      def say(t: String, button: Boolean = false) = tell(env, chat, t, if button then origin else "")
      val done: Future[Any] =
        if chat.isEmpty || !privately then Future.successful(())
        else if text.startsWith("/start ") then
          val code = text.drop(7).trim
          val links = env.LINKS
          links.get(links.idFromName(code)).fetch("https://link/take").asInstanceOf[js.Promise[js.Dynamic]].toFuture
            .flatMap(r => r.text().asInstanceOf[js.Promise[String]].toFuture).flatMap { room =>
              if !room.matches("[0-9a-f]{64}") then
                say("This link is not valid any more. Make a new one on okay-remote's page: Phone → Telegram.")
              else
                val rooms = env.ROOMS
                rooms.get(rooms.idFromName(room)).fetch(s"https://room/bind?chat=$chat")
                  .asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { r =>
                    if r.status.asInstanceOf[Int] == 200 then say("Linked. This chat is told when okay-remote " +
                      "waits for you, and when an agent is done — only that, never what.", button = true)
                    else say("okay-remote is not running on your computer: start it and make a new link.")
                  }
            }
        else say("To stop these messages, press Unlink on okay-remote's page (Phone → Telegram), or block this bot.")
      done.map(_ => Front.response(200, "ok", "text/plain")).recover { case _ => Front.response(200, "ok",
        "text/plain") }
    }
