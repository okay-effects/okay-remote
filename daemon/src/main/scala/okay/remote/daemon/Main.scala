package okay.remote.daemon

import okay.effects.json.{Descent, Json}
import okay.guard.jvm.JvmOs
import okay.guard.local.{Stream, Web, WebRequest}
import okay.remote.{Agent, J, Jail, Judge, Machine, Remote, Secret}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** THE DAEMON: `java -jar okay-remote.jar [--port 7788] [--lan]` — the remote over the JVM (okay-guard's `JvmOs`, its
 * one loop), the page's server: the page, its events, its acts — and the agents' hook */
object Main:
  def main(args: Array[String]): Unit = JvmOs.loop.execute(() =>
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
  def append(file: String, text: String): Unit = JvmOs.append(file, text)
  def remove(file: String): Unit = JvmOs.remove(file)
  def list(dir: String): List[String] = JvmOs.list(dir)
  def real(path: String): String =
    scala.util.Try(java.nio.file.Paths.get(path).toRealPath().toString).getOrElse(path)
  def log(text: String): Unit = JvmOs.err(text)

  private def onLoop(f: => Unit): Unit = JvmOs.loop.execute(() => f)
  private def thread(name: String)(f: => Unit): Unit =
    val t = new Thread(() => f, name); t.setDaemon(true); t.start()
  private def started(command: List[String], dir: String, env: Map[String, String]) = scala.util.Try {
    val b = new ProcessBuilder(command.asJava).directory(new java.io.File(dir))
    env.foreach((k, v) => b.environment().put(k, v))
    b.redirectError(ProcessBuilder.Redirect.INHERIT).start()
  }

  def spawn(command: List[String], dir: String, env: Map[String, String]): Agent =
    var onLine: String => Unit = _ => ()
    var onEnd: String => Unit = _ => ()
    val p = started(command, dir, env)
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
    started(command, dir, Map.empty) match
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
    val rozum = sys.env.getOrElse("PATH", "").split(':').exists(d => java.nio.file.Files.isExecutable(
      java.nio.file.Paths.get(d, "rozum")))
    Jail(rozum, read(s"$base/claude-token").map(_.trim).filter(_.nonEmpty))

final class Daemon(args: List[String]):
  private def opt(name: String): Option[String] = args.dropWhile(_ != name).drop(1).headOption
  private val lan = args.contains("--lan")
  private var port = opt("--port").flatMap(_.toIntOption).getOrElse(7788)
  private val pages = mutable.Buffer.empty[Stream]
  given scala.concurrent.ExecutionContext = JvmOs.ec
  private def tell(event: String, data: Json): Unit =
    val text = s"event: $event\ndata: ${Json.print(data)}\n\n"
    pages.toList.foreach(_.write(text))
    relay.foreach(_.send("event", "name" -> Json.Str(event), "data" -> data))
  private val remote = Remote(OnJvm, () => port, tell, Judge(_), () => jail)
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

  private def phones(kind: String, j: Json): Unit = kind match
    case "hello" => relay.foreach(_.send("event", "name" -> Json.Str("hello"), "data" -> remote.snapshot))
    case "act" =>
      val id = J.get(j, "id").getOrElse(Json.Null)
      remote.act(J.get(j, "body").getOrElse(Json.Null)) { r =>
        relay.foreach(_.send("answer", "id" -> id, "ok" -> Json.Bool(r.isRight),
          "body" -> r.fold(e => Json.obj("error" -> Json.Str(e)), identity)))
      }
    case _ => ()

  def start(): Unit =
    jail = OnJvm.jail(remote.base)
    OnJvm.write(relayFile, Json.print(Json.obj("on" -> Json.Bool(relayOn), "url" -> Json.Str(relayUrl),
      "secret" -> Json.Str(keys.text))))
    remote.start()
    if relayOn then
      val r = Relay(relayUrl, keys, phones, said => JvmOs.err(s"okay-remote: $said\n"))
      relay = Some(r)
      r.start()
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
        "pair" -> Json.Str(if local && relayOn then s"${relayUrl.stripSuffix("/")}/#s=${keys.text}" else "")))
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
        case Right(body) => remote.act(body)(_.fold(error(w, 400, _), answer(w, 200, _)))
    else error(w, 404, "no such place")

object Daemon:
  val hosted = "https://okay-remote.okay-effects.workers.dev"

  /** `okay-remote verify`: every session's record checked, its chain unbroken or where it breaks */
  def verify(): Unit =
    given scala.concurrent.ExecutionContext = JvmOs.ec
    val r = Remote(OnJvm, () => 0, (_, _) => (), Judge(_))
    r.start()
    val checked = r.verify
    checked.foreach {
      case (id, Right(n)) => JvmOs.out(s"$id: $n records, the chain unbroken\n")
      case (id, Left(e)) => JvmOs.out(s"$id: BROKEN — $e\n")
    }
    JvmOs.exit(if checked.forall(_._2.isRight) then 0 else 1)

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
