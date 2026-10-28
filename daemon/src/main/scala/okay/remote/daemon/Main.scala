package okay.remote.daemon

import okay.effects.json.{Descent, Json}
import okay.guard.jvm.JvmOs
import okay.guard.local.{Stream, Web, WebRequest}
import okay.remote.{Agent, Judge, Machine, Remote, Secret}
import scala.collection.mutable

/** THE DAEMON: `java -jar okay-remote.jar [--port 7788] [--lan]` — the remote over the JVM (okay-guard's `JvmOs`, its
 * one loop), the page's server: the page, its events, its acts — and the agents' hook */
object Main:
  def main(args: Array[String]): Unit = JvmOs.loop.execute(() => new Daemon(args.toList).start())

/** the JVM as the remote's machine: okay-guard's `JvmOs`, and a process started in a directory */
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
  def spawn(command: List[String], dir: String): Agent =
    // the shell goes to the directory and becomes the command: its arguments as they are, never a shell's words
    val p = JvmOs.spawn(List("/bin/sh", "-c", "cd \"$0\" && exec \"$@\"", dir) ++ command)
    new Agent:
      def send(line: String): Unit = p.send(line)
      def lines(f: String => Unit): Unit = p.lines(f)
      def ended(f: String => Unit): Unit =
        p.ended(code => f(s"ended ($code)"))
        p.failed(e => f(s"not started: $e"))
      def kill(): Unit = p.kill()

final class Daemon(args: List[String]):
  private def opt(name: String): Option[String] = args.dropWhile(_ != name).drop(1).headOption
  private val lan = args.contains("--lan")
  private var port = opt("--port").flatMap(_.toIntOption).getOrElse(7788)
  private val pages = mutable.Buffer.empty[Stream]
  given scala.concurrent.ExecutionContext = JvmOs.ec
  private def tell(event: String, data: Json): Unit =
    val text = s"event: $event\ndata: ${Json.print(data)}\n\n"
    pages.toList.foreach(_.write(text))
  private val remote = Remote(OnJvm, () => port, tell, Judge(_))
  private val token: String = OnJvm.read(s"${remote.base}/token").map(_.trim).filter(_.length >= 32).getOrElse {
    val t = OnJvm.randomHex(24); OnJvm.write(s"${remote.base}/token", t); t
  }

  def start(): Unit =
    remote.start()
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
  private def hello: Json = remote.snapshot match
    case o: Json.Obj => Json.Obj(o.fields :+ ("addresses" -> Json.Arr((if lan then JvmOs.lanAddresses else Nil)
        .map(Json.Str(_)).toVector)))
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
      s.write(s"event: hello\ndata: ${Json.print(hello)}\n\n")
    else if r.method == "POST" && r.path == "/act" then
      if !sameOrigin(r) then error(w, 403, "not from this page")
      else Descent.parse(r.body) match
        case Left(_) => error(w, 400, "not JSON")
        case Right(body) => remote.act(body).fold(error(w, 400, _), answer(w, 200, _))
    else error(w, 404, "no such place")

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
