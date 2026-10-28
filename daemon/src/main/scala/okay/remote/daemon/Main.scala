package okay.remote.daemon

import okay.effects.json.{Descent, Json}
import okay.guard.jvm.JvmOs
import okay.guard.local.{Stream, Web, WebRequest}
import okay.guard.local.JournalFiles
import okay.remote.{Agent, Audit, J, Jail, Judge, Machine, Remote, Secret}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** THE DAEMON: `java -jar okay-remote.jar [--port 7788] [--lan]` — the remote over the JVM (okay-guard's `JvmOs`, its
 * one loop), the page's server: the page, its events, its acts — and the agents' hook */
object Main:
  val version = "0.1.0"
  val help: String =
    s"""okay-remote $version — your coding agents, on your machine, from your phone
       |
       |  okay-remote --relay           start; your phone reaches it from anywhere (pair it: open the address
       |                                printed, then Phone, and scan the QR code)
       |  okay-remote                   start, for this machine only (or as remembered from --relay before)
       |  okay-remote --lan             start; a phone on the same network reaches it
       |  okay-remote verify            check every session's record (its SHA-256 chain)
       |
       |  --port N            the page's port (7788)
       |  --relay URL         your own relay (relay/ in the repository) instead of the hosted one
       |  --no-relay          forget the relay
       |  --pair-again        a new pairing secret: every paired phone is out
       |  --version, --help
       |
       |Files: ~/.okay-remote (token, relay.json, policy.policy, sessions/, roots, claude-token).
       |Docs: https://github.com/okay-effects/okay-remote""".stripMargin
  def main(args: Array[String]): Unit =
    if args.contains("--help") || args.contains("-h") then { println(help); sys.exit(0) }
    if args.contains("--version") then { println(s"okay-remote $version"); sys.exit(0) }
    JvmOs.loop.execute(() =>
      if args.headOption.contains("verify") then Daemon.verify()
      else new Daemon(args.toList).start())

/** the JVM as the remote's machine: okay-guard's `JvmOs` (its loop, its files), and processes started here in a
 * directory with an environment — their output read on threads of their own, handed to the loop */
object OnJvm extends Machine:
  def now: Long = JvmOs.now
  def later(ms: Int)(f: => Unit): Unit = JvmOs.later(ms)(f)
  def randomHex(bytes: Int): String = JvmOs.randomHex(bytes)
  def home: String = JvmOs.userHome
  def read(file: String): Option[String] = JvmOs.read(file)
  def write(file: String, text: String): Unit = JvmOs.write(file, text, secret = true)
  def save(file: String, text: String): Unit = JvmOs.write(file, text, secret = false)
  def onPath(name: String): Boolean = sys.env.getOrElse("PATH", "").split(':').exists(d =>
    java.nio.file.Files.isExecutable(java.nio.file.Paths.get(d, name)))
  def append(file: String, text: String): Unit = JvmOs.append(file, text)
  def remove(file: String): Unit = JvmOs.remove(file)
  def list(dir: String): List[String] = JvmOs.list(dir)
  def real(path: String): String =
    scala.util.Try(java.nio.file.Paths.get(path).toRealPath().toString).getOrElse(path)
  def log(text: String): Unit = JvmOs.err(text)

  private def onLoop(f: => Unit): Unit = JvmOs.loop.execute(() => f)
  /** every process started here: stopped, with all they started, when the daemon stops — no agent outlives it */
  private val started = java.util.concurrent.ConcurrentHashMap.newKeySet[Process]()
  Runtime.getRuntime.addShutdownHook(new Thread(() => started.forEach { p =>
    p.descendants().forEach(d => { val _ = d.destroy() }); p.destroy()
  }))
  private def thread(name: String)(f: => Unit): Unit =
    val t = new Thread(() => f, name); t.setDaemon(true); t.start()
  private def start(command: List[String], dir: String, env: Map[String, String]) = scala.util.Try {
    val b = new ProcessBuilder(command.asJava).directory(new java.io.File(dir))
    // an agent started here is not inside the session that may have started the daemon
    List("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT").foreach(b.environment().remove)
    env.foreach((k, v) => b.environment().put(k, v))
    val p = b.redirectError(ProcessBuilder.Redirect.INHERIT).start()
    started.add(p)
    p.onExit().thenRun(() => { val _ = started.remove(p) })
    p
  }

  def spawn(command: List[String], dir: String, env: Map[String, String]): Agent =
    var onLine: String => Unit = _ => ()
    var onEnd: String => Unit = _ => ()
    val p = start(command, dir, env)
    p match
      case scala.util.Failure(e) => JvmOs.later(0)(onEnd(s"not started: ${e.getMessage}"))
      case scala.util.Success(proc) => thread("okay-remote-agent") {
          val r = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream, UTF_8))
          try Iterator.continually(r.readLine()).takeWhile(_ != null).filter(_.trim.nonEmpty)
            .foreach(l => onLoop(onLine(l)))
          catch { case _: java.io.IOException => () }
          val code = proc.waitFor()
          onLoop(onEnd(s"ended ($code)"))
        }
    val stdin = p.toOption.map(_.getOutputStream)
    new Agent:
      def send(line: String): Unit = stdin.foreach { o =>
        try { o.write((line + "\n").getBytes(UTF_8)); o.flush() } catch { case _: java.io.IOException => () }
      }
      def lines(f: String => Unit): Unit = onLine = f
      def ended(f: String => Unit): Unit = onEnd = f
      // the whole tree: rozum's agent is its child
      def kill(): Unit = p.foreach { proc =>
        proc.descendants().forEach(d => { val _ = d.destroy() }); proc.destroy()
      }

  def run(command: List[String], dir: String, limit: Int)(done: (Int, String) => Unit): Unit =
    start(command, dir, Map.empty) match
      case scala.util.Failure(e) => JvmOs.later(0)(done(-1, s"not started: ${e.getMessage}"))
      case scala.util.Success(proc) => thread("okay-remote-run") {
          proc.getOutputStream.close()
          val out = new String(proc.getInputStream.readNBytes(limit), UTF_8)
          proc.getInputStream.close()
          val code = proc.waitFor()
          onLoop(done(code, out))
        }

  /** what this machine has for the jail: rozum on the PATH, a token for Claude in `~/.okay-remote/claude-token` */
  def jail(base: String): Jail =
    Jail(onPath("rozum"), read(s"$base/claude-token").map(_.trim).filter(_.nonEmpty))

final class Daemon(args: List[String]):
  private def opt(name: String): Option[String] = args.dropWhile(_ != name).drop(1).headOption
  private val lan = args.contains("--lan")
  private var port = opt("--port").flatMap(_.toIntOption).getOrElse(7788)
  private val pages = mutable.Buffer.empty[Stream]
  given scala.concurrent.ExecutionContext = JvmOs.ec
  private def tell(event: String, data: Json): Unit =
    // Telegram told only that something waits or ended, and where — never what
    (event, J.str(data, "session")) match
      case ("waiting", Some(id)) => notify(id, "waiting")
      case ("step", Some(id)) => J.get(data, "step").flatMap(J.str(_, "k")) match
          case Some("ended") => notify(id, "done")
          case Some("stopped") => notify(id, "stopped")
          case _ => ()
      case _ => ()
    val text = s"event: $event\ndata: ${Json.print(data)}\n\n"
    pages.toList.foreach(_.write(text))
    relay.foreach(_.send("event", "name" -> Json.Str(event), "data" -> data))
  private val remote = Remote(OnJvm, () => port, tell, Judge(_), () => jail, Daemon.audit(OnJvm.home))
  private var jail = Jail()
  private val token: String = OnJvm.read(s"${remote.base}/token").map(_.trim).filter(_.length >= 32).getOrElse {
    val t = OnJvm.randomHex(24); OnJvm.write(s"${remote.base}/token", t); t
  }

  // ---- THE RELAY (specs/remote.md): on with `--relay [url]`, remembered; `--pair-again` a new secret
  private val relayFile = s"${remote.base}/relay.json"
  private val kept = OnJvm.read(relayFile).flatMap(t => okay.effects.json.Descent.parse(t).toOption)
  private val relayUrl: String = args.dropWhile(_ != "--relay").drop(1).headOption.filterNot(_.startsWith("--"))
    .orElse(kept.flatMap(J.str(_, "url"))).getOrElse(Daemon.hosted)
  private val relayOn: Boolean = !args.contains("--no-relay") && (args.contains("--relay") ||
    kept.exists(J.bool(_, "on")))
  private val keys: Keys = (if args.contains("--pair-again") then None
    else kept.flatMap(J.str(_, "secret")).flatMap(Keys.of)).getOrElse(Keys.fresh())
  private var relay: Option[Relay] = None

  // ---- TELEGRAM (specs/remote.md): the bot's name (the relay's, if it has one), the bound chat and its signature
  private var bot = ""
  private var chat: Option[(String, String)] =
    for k <- kept; c <- J.str(k, "chat"); s <- J.str(k, "sig") if c.nonEmpty yield (c, s)
  private def keepRelay(): Unit =
    OnJvm.write(relayFile, Json.print(Json.Obj(Vector("on" -> Json.Bool(relayOn), "url" -> Json.Str(relayUrl),
      "secret" -> Json.Str(keys.text)) ++ chat.toVector.flatMap((c, s) => Vector("chat" -> Json.Str(c),
      "sig" -> Json.Str(s))))))
  private def fromRelay(line: String): Unit =
    val parts = line.split(' ').toList
    parts match
      case List("bound", c, s) => chat = Some((c, s)); keepRelay(); tell("telegram", telegram)
        JvmOs.err("okay-remote: Telegram linked\n")
      case List("unbound") => chat = None; keepRelay(); tell("telegram", telegram)
      case _ => ()
  private def telegram: Json = Json.obj("bot" -> Json.Str(bot), "linked" -> Json.Bool(chat.nonEmpty))
  /** the last time each session was told of, by kind: one message a minute at most */
  private val toldAt = mutable.Map.empty[(String, String), Long]
  private def notify(session: String, kind: String): Unit = if chat.nonEmpty then
    val now = OnJvm.now
    if toldAt.get((session, kind)).forall(now - _ > 60000) then
      toldAt((session, kind)) = now
      val project = remote.about(session).map(_._1.split('/').last).getOrElse("a project")
      val text = kind match
        case "waiting" => s"okay-remote · $project: a call waits for you"
        case "done" => s"okay-remote · $project: the agent is done"
        case _ => s"okay-remote · $project: the agent stopped"
      relay.foreach(_.plain("notify " + Json.print(Json.obj("text" -> Json.Str(text)))))

  /** the daemon's own acts (Telegram), else the remote's */
  private def act(body: Json)(answer: Either[String, Json] => Unit): Unit = J.str(body, "type") match
    case Some("telegram-link") =>
      if bot.isEmpty || relay.isEmpty then answer(Left("no Telegram bot at this relay"))
      else
        val code = OnJvm.randomHex(12)
        relay.foreach(_.plain(s"link $code"))
        answer(Right(Json.obj("url" -> Json.Str(s"https://t.me/$bot?start=$code"))))
    case Some("telegram-unlink") =>
      chat = None; keepRelay(); relay.foreach(_.plain("unchat")); tell("telegram", telegram)
      answer(Right(Json.Obj.empty))
    case _ => remote.act(body)(answer)

  private def phones(kind: String, j: Json): Unit = kind match
    case "hello" => relay.foreach(_.send("event", "name" -> Json.Str("hello"), "data" -> hello(false)))
    case "act" =>
      val id = J.get(j, "id").getOrElse(Json.Null)
      act(J.get(j, "body").getOrElse(Json.Null)) { r =>
        relay.foreach(_.send("answer", "id" -> id, "ok" -> Json.Bool(r.isRight),
          "body" -> r.fold(e => Json.obj("error" -> Json.Str(e)), identity)))
      }
    case _ => ()

  def start(): Unit =
    jail = OnJvm.jail(remote.base)
    if args.contains("--pair-again") then chat = None
    keepRelay()
    remote.start()
    if relayOn then
      val r = Relay(relayUrl, keys, phones, said => JvmOs.err(s"okay-remote: $said\n"), fromRelay,
        () => chat.foreach((c, s) => relay.foreach(_.plain(s"chat $c $s"))))
      relay = Some(r)
      r.start()
      Daemon.botOf(relayUrl) { name => bot = name; tell("telegram", telegram) }
    JvmOs.err(s"okay-remote: the sandbox: ${if jail.rozum then "rozum" else "none (rozum is not installed)"}" +
      s"; Claude ${if jail.claudeJailed then "in it" else "outside it (no ~/.okay-remote/claude-token)"}\n")
    JvmOs.serve(if lan then "0.0.0.0" else "127.0.0.1", port)(serve) { p =>
      port = p
      val here = s"http://127.0.0.1:$p/?t=$token"
      JvmOs.err(s"okay-remote: open $here\n")
      if lan then JvmOs.lanAddresses.foreach(a =>
        JvmOs.err(s"  on your phone (same network): http://$a:$p/?t=$token\n"))
      JvmOs.err(s"  projects under ${remote.roots.mkString(", ")} (${remote.projects.size}); " +
        s"the policy: ${remote.base}/policy.policy\n")
    }

  /** what a page is told first: the remote's snapshot, and where a phone reaches this daemon (`--lan`) */
  private def hello(local: Boolean): Json = remote.snapshot match
    case o: Json.Obj => Json.Obj(o.fields ++ Vector(
        "addresses" -> Json.Arr((if lan then JvmOs.lanAddresses else Nil).map(Json.Str(_)).toVector),
        // the pairing's link (its secret in the fragment) only to a page on this very machine
        "pair" -> Json.Str(if local && relayOn then s"${relayUrl.stripSuffix("/")}/#s=${keys.text}" else ""),
        "telegram" -> telegram))
    case other => other

  private val json = List("Content-Type" -> "application/json", "Cache-Control" -> "no-store")
  private def answer(w: Web, status: Int, j: Json): Unit = w.reply(status, json, Json.print(j))
  private def error(w: Web, status: Int, why: String): Unit = answer(w, status, Json.obj("error" -> Json.Str(why)))

  /** a host this daemon is: its own name or address (a page of another site, renamed to here, is not let in) */
  private def ours(r: WebRequest): Boolean =
    val host = r.headers.getOrElse("host", "").reverse.dropWhile(_ != ':').drop(1).reverse
    Set("127.0.0.1", "localhost").contains(host) || (lan && JvmOs.lanAddresses.contains(host))
  /** a POST from a page: from this daemon's own page, or from no page at all */
  private def sameOrigin(r: WebRequest): Boolean = r.headers.get("origin").forall(o =>
    o == s"http://${r.headers.getOrElse("host", "")}")

  private def serve(r: WebRequest, w: Web): Unit =
    val t = r.query.getOrElse("t", r.headers.getOrElse("x-okay-token", ""))
    if !ours(r) then error(w, 421, "not this host")
    else if r.method == "POST" && r.path.startsWith("/hook/") then
      Descent.parse(r.body) match
        case Left(_) => error(w, 400, "not JSON")
        case Right(body) => remote.hook(r.path.stripPrefix("/hook/"), r.query.getOrElse("k", ""), body) {
            case Right(out) => answer(w, 200, out)
            case Left((status, why)) => error(w, status, why)
          }
    else if r.method == "GET" && Page.files.contains(r.path) then
      Page.file(r.path).fold(error(w, 404, "not built"))((kind, text) => w.reply(200, List("Content-Type" -> kind,
        "Cache-Control" -> "no-cache", "X-Content-Type-Options" -> "nosniff",
        "Content-Security-Policy" -> "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:",
        "Referrer-Policy" -> "no-referrer"), text))
    else if r.method == "GET" && r.path.matches("/internal-[0-9a-f]+\\.js") then
      Page.module(r.path.drop(1)).fold(error(w, 404, "no such module"))(text =>
        w.reply(200, List("Content-Type" -> "text/javascript", "X-Content-Type-Options" -> "nosniff"), text))
    else if !Secret.same(t, token) then error(w, 401, "the token is wrong")
    else if r.method == "GET" && r.path == "/events" then
      val s = w.stream(List("Content-Type" -> "text/event-stream", "Cache-Control" -> "no-store"))
      pages += s
      s.closed(() => pages -= s)
      val host = r.headers.getOrElse("host", "")
      s.write(s"event: hello\ndata: ${Json.print(hello(host.startsWith("127.0.0.1:") ||
        host.startsWith("localhost:")))}\n\n")
    else if r.method == "POST" && r.path == "/act" then
      if !sameOrigin(r) then error(w, 403, "not from this page")
      else Descent.parse(r.body) match
        case Left(_) => error(w, 400, "not JSON")
        case Right(body) => act(body)(_.fold(error(w, 400, _), answer(w, 200, _)))
    else error(w, 404, "no such place")

object Daemon:
  val hosted = "https://okay-remote.okay-effects.workers.dev"

  /** the relay's bot, if it has one (`/tg/info`): its name to `got`, on the loop */
  def botOf(relay: String)(got: String => Unit): Unit =
    val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(relay.stripSuffix("/") + "/tg/info"))
      .timeout(java.time.Duration.ofSeconds(20)).build()
    java.net.http.HttpClient.newHttpClient().sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofString())
      .thenAccept { r =>
        if r.statusCode == 200 then okay.effects.json.Descent.parse(r.body).toOption.flatMap(J.str(_, "bot"))
          .filter(_.matches("[A-Za-z0-9_]{5,64}")).foreach(name => JvmOs.loop.execute(() => got(name)))
      }

  /** okay-guard's audit journal of this daemon: `~/.okay-remote/journal/<day>.jsonl`, chained, the policies by hash */
  def journal(home: String): JournalFiles = new JournalFiles(JvmOs, s"$home/.okay-remote")
  def audit(home: String): Audit =
    val files = journal(home)
    new Audit:
      def record(r: Json): Unit =
        files.append(r, J.long(r, "ended").max(J.long(r, "at")).max(0L) match { case 0L => JvmOs.now; case t => t })
      def policy(hash: String, text: String): Unit = files.keepPolicy(hash, text)

  /** `okay-remote verify`: okay-guard's journal (every action, chained), and every session's record */
  def verify(): Unit =
    given scala.concurrent.ExecutionContext = JvmOs.ec
    val r = Remote(OnJvm, () => 0, (_, _) => (), Judge(_))
    r.start()
    val journal = Daemon.journal(OnJvm.home).verify
    JvmOs.out(journal.fold(e => s"the journal: BROKEN — $e\n", ok => s"the journal: $ok\n"))
    val checked = r.verify
    checked.foreach {
      case (id, Right(n)) => JvmOs.out(s"$id: $n records, the chain unbroken\n")
      case (id, Left(e)) => JvmOs.out(s"$id: BROKEN — $e\n")
    }
    JvmOs.exit(if journal.isRight && checked.forall(_._2.isRight) then 0 else 1)

/** THE PAGE'S FILES: its html, manifest, worker, icon (site/), its modules (web/, linked) — in the jar */
object Page:
  val files: Map[String, (String, String)] = Map(
    "/" -> ("index.html", "text/html; charset=utf-8"),
    "/manifest.webmanifest" -> ("manifest.webmanifest", "application/manifest+json"),
    "/sw.js" -> ("sw.js", "text/javascript"),
    "/icon.svg" -> ("icon.svg", "image/svg+xml"),
    "/page.js" -> ("page.js", "text/javascript"))
  def file(path: String): Option[(String, String)] =
    files.get(path).flatMap((name, kind) => module(name).map(kind -> _))
  def module(name: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/okay-remote-page/$name")).map { in =>
      try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally in.close()
    }
