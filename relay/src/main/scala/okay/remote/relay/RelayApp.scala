package okay.remote.relay

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.*
import okay.serverless.cloud.*
import okay.serverless.cloudflare.Worker
import okay.serverless.relay.{Hashes, Rooms, Steward, Tokens}
import okay.serverless.telegram.Telegram
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * OKAY-REMOTE'S RELAY (specs/remote.md): an okay-serverless app. It gives the page (the same page as the daemon's),
 * joins a daemon and its phones in okay-serverless's sealed `Rooms` — frames it cannot read, nothing kept — and is
 * a
 * Telegram bot that tells a linked chat when something waits (only that, never what). Nothing here names Cloudflare
 * but the last line.
 */
object RelayApp:
  private var files: Map[String, String] = Map.empty
  @JSExportTopLevel("setFiles") def setFiles(f: js.Dictionary[String]): Unit = files = f.toMap

  private val kinds = Map("html" -> "text/html; charset=utf-8", "js" -> "text/javascript; charset=utf-8",
    "webmanifest" -> "application/manifest+json", "svg" -> "image/svg+xml")
  val secure: Map[String, String] = Map("x-content-type-options" -> "nosniff", "referrer-policy" -> "no-referrer",
    "x-frame-options" -> "DENY", "strict-transport-security" -> "max-age=31536000", "cache-control" -> "no-cache",
    "content-security-policy" -> ("default-src 'self'; connect-src 'self'; style-src 'self' 'unsafe-inline'; " +
      "img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
  private def answer(status: Int, body: String, kind: String = "text/plain; charset=utf-8"): Response =
    Response(status, secure + ("content-type" -> kind), body)

  // ---- THE BOT'S CONFIGURATION: the relay's secrets; none, no Telegram here

  final case class Bot(token: String, secret: String, name: String, api: String, origin: Option[String])
  def bot[R[_]](using In[Env, R]): Option[Bot] ! R =
    for
      token <- Env.secret[R]("TG_TOKEN")
      secret <- Env.secret[R]("TG_SECRET")
      name <- Env.secret[R]("TG_BOT")
      api <- Env.variable[R]("TG_API")
      origin <- Env.variable[R]("ORIGIN")
    yield for t <- token; s <- secret; n <- name yield Bot(t, s, n, api.getOrElse("https://api.telegram.org"),
      origin.filter(_.startsWith("https://")))

  private def told[R[_], A](b: Bot)(p: A ! (R + Telegram))(using In[Fetch, R]): A ! R =
    Telegram.viaBotApi[R + Telegram, R, A](b.token, b.api)(p)

  /** a code for a chat, for ten minutes: what the bot sends to the chat and the person types on the page — proof the
   * chat is theirs, with no message to the bot and nothing kept (`window` the ten minutes it belongs to) */
  def code(b: Bot, room: String, chat: String, window: Long): String =
    val mac = Hashes.hmac(Hashes.utf8(b.secret), Hashes.utf8(s"code:$room:$chat:$window"))
    f"${(BigInt(1, mac.take(6)) % 1000000).toInt}%06d"

  /** a chat's signature: the relay's HMAC of the room and the chat — what a daemon gives back to be told again */
  def sign(b: Bot, room: String, chat: String): String =
    Hashes.hex(Hashes.hmac(Hashes.utf8(b.secret), Hashes.utf8(s"$room:$chat")))

  /** THE STEWARD OF THE ROOMS: the daemon's bound chat (from its request, signed), its control lines (a linking code,
   * a notification), and a chat bound by Telegram's `/start` (`/bind`, the code checked) */
  object Stewarding extends Steward:
    override def hostTags(room: String, request: Request): List[String] ! InActor =
      bot[InActor].map(_.toList.flatMap(b => for
        chat <- request.param("chat").toList if chat.matches("-?[0-9]{1,20}")
        sig <- request.param("sig").toList if Hashes.same(sig, sign(b, room, chat))
      yield s"chat:$chat"))

    override def control(room: String, host: Socket, line: String): Unit ! InActor =
      val (word, rest) = line.span(_ != ' ')
      bot[InActor].flatMap {
        case None => pure(())
        case Some(b) => word match
          case "link" => Clock.now[InActor].flatMap(now =>
              Sockets.send[InActor](host, "ctl:code " + Tokens.sign(room, b.secret, now + 600000)))
          case "notify" =>
            val text = okay.effects.json.Descent.parse(rest.trim).toOption.flatMap {
              case o: Json.Obj => o.get("text").collect { case Json.Str(t) => t }
              case _ => None
            }.getOrElse("okay-remote").take(300)
            host.tags.collectFirst { case t if t.startsWith("chat:") => t.drop(5) } match
              case None => pure(())
              case Some(chat) => told[InActor, Boolean](b)(Telegram.send[InActor + Telegram](chat, text,
                  b.origin.map(_ + "/"))).flatMap(ok =>
                  if ok then pure(()) else Sockets.send[InActor](host, "ctl:unbound"))
          // LINKING BY A CODE (no message to the bot needed: a bot that is read by polling elsewhere stays so): a code
          // sent to the chat; typed on the page, it binds the chat
          case "verify" =>
            val chat = rest.trim
            if !chat.matches("-?[0-9]{1,20}") then Sockets.send[InActor](host, "ctl:nochat")
            else Clock.now[InActor].flatMap(now => told[InActor, Boolean](b)(Telegram.send[InActor + Telegram](chat,
              s"okay-remote: your code is ${code(b, room, chat, now / 600000)} (for ten minutes). If you did not ask " +
                "for it, ignore this.")).flatMap(ok => if ok then pure(()) else
                Sockets.send[InActor](host, "ctl:nochat")))
          case "confirm" =>
            val (chat, typed) = rest.trim.span(_ != ' ')
            Clock.now[InActor].flatMap { now =>
              val window = now / 600000
              val right = chat.matches("-?[0-9]{1,20}") &&
                (Hashes.same(typed.trim, code(b, room, chat, window)) ||
                  Hashes.same(typed.trim, code(b, room, chat, window - 1)))
              if !right then Sockets.send[InActor](host, "ctl:wrongcode")
              else Sockets.send[InActor](host, s"ctl:bound $chat ${sign(b, room, chat)} code")
            }
          // the daemon's word on a bound chat (its own one-time code checked): told so — only a chat of this room,
          // by the relay's signature
          case "linked" | "refused" =>
            val (chat, sig) = rest.trim.span(_ != ' ')
            if !chat.matches("-?[0-9]{1,20}") || !Hashes.same(sig.trim, sign(b, room, chat)) then pure(())
            else told[InActor, Boolean](b)(Telegram.send[InActor + Telegram](chat,
              if word == "linked" then "Linked. This chat is told when okay-remote waits for you, and when an " +
                "agent is done — only that, never what."
              else "This link was used already, or has expired. Make a new one on okay-remote's page: Phone → " +
                "Telegram.", if word == "linked" then b.origin.map(_ + "/") else None)).as(())
          case _ => pure(())
      }

    override def call(room: String, request: Request): Option[Response ! InActor] =
      if request.path.takeWhile(_ != '?') != "/bind" then None
      else Some(bot[InActor].flatMap {
        case None => pure(answer(404, "no bot here"))
        case Some(b) =>
          val chat = request.param("chat").getOrElse("")
          val code = request.param("code").getOrElse("")
          Clock.now[InActor].flatMap { now =>
            if !chat.matches("-?[0-9]{1,20}") || !Tokens.check(code, b.secret, now).contains(room) then
              pure(answer(403, "not a code of this room"))
            else Sockets.open[InActor](Some("host")).flatMap {
              case Nil => pure(answer(409, "no daemon"))
              case hosts => hosts.foldLeft(pure[Unit, InActor](()))((p, h) =>
                  p.andThen(Sockets.send[InActor](h, s"ctl:bound $chat ${sign(b, room, chat)} $code")))
                  .as(answer(200, "bound"))
            }
          }
      })

  /** THE APP: the rooms by their route; the bot's webhook and its name; the page's files */
  object Front extends App:
    override def objectFor(request: Request): Option[(String, String)] = Rooms.route(request, "ROOMS")

    override def fetch(request: Request): Response ! Cloud = request.path.takeWhile(_ != '?') match
      case "/tg/info" => bot[Cloud].map {
          case Some(b) => answer(200, Json.print(Json.obj("bot" -> Json.Str(b.name))), "application/json")
          case None => answer(404, "no bot here")
        }
      case "/tg" if request.method == "POST" => bot[Cloud].flatMap {
          case None => pure(answer(404, "no bot here"))
          case Some(b) => Telegram.update(request, b.secret) match
            case Left(refused) => pure(refused)
            case Right(Some(u)) if u.privately => told[Cloud, Unit](b)(started(b, u)).as(answer(200, "ok"))
            case Right(_) => pure(answer(200, "ok"))
        }
      case _ if request.method != "GET" && request.method != "HEAD" => pure(answer(405, "GET only"))
      case path =>
        val name = if path == "/" then "index.html" else path.drop(1)
        pure(files.get(name) match
          case Some(text) if !name.contains("/") =>
            answer(200, text, kinds.getOrElse(name.reverse.takeWhile(_ != '.').reverse, "text/plain"))
          case _ => answer(404, "no such page"))

    /** `/start <code>`: the chat bound to the room the code names — or told why not */
    private def started(b: Bot, u: Telegram.Update): Unit ! (Cloud + Telegram) =
      type T[X] = (Cloud + Telegram)[X]
      def say(text: String) = Telegram.send[T](u.chat, text, None).as(())
      if !u.text.startsWith("/start ") then
        say("To stop these messages, press Unlink on okay-remote's page (Phone → Telegram), or block this bot.")
      else
        val code = u.text.drop(7).trim
        Clock.now[T].flatMap(now => Tokens.check(code, b.secret, now) match
          case None => say("This link is not valid any more. Make a new one on okay-remote's page: Phone → Telegram.")
          case Some(room) =>
            // bound: the daemon says so itself (`linked`), once it has checked its code
            Objects.call[T]("ROOMS", room, Request("POST", s"/bind?chat=${u.chat}&code=$code")).flatMap(r =>
              if r.status == 200 then pure(())
              else say("okay-remote is not running on your computer: start it and make a new link.")))

  @JSExportTopLevel("worker")
  val worker: Worker = Worker(Front, Map("ROOMS" -> Rooms(Stewarding)))
