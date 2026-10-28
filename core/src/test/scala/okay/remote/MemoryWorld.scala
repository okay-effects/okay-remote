package okay.remote

import okay.effects.json.{Descent, Json}
import okay.scala3.std.{Clock, Log, Random}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}

/** A PLACE IN MEMORY, for tests: files a map, processes the test speaks for, the pages' events kept, the time still,
 * the timers fired by the test, numbers counted */
final class MemoryWorld(dirs: String => List[String] = _ => Nil, has: String => Boolean = _ == "claude"):
  val files = mutable.Map.empty[String, String]
  val saved = mutable.Map.empty[String, String]
  val told = mutable.Buffer.empty[(String, Json)]
  val logged = mutable.Buffer.empty[String]

  /** a process the test speaks for */
  final class Proc(val command: List[String], val dir: String, val env: Map[String, String]):
    val sent = mutable.Buffer.empty[String]
    var onLine: String => Unit = _ => ()
    var onEnd: String => Unit = _ => ()
    var killed = false
    val agent: Agent = new Agent:
      def send(l: String) = { sent += l; () }
      def lines(f: String => Unit) = onLine = f
      def ended(f: String => Unit) = onEnd = f
      def kill() = { killed = true; onEnd("killed") }
    def say(j: String) = onLine(j)
    def json: Vector[Json] = sent.toVector.flatMap(l => Descent.parse(l).toOption)
  val procs = mutable.Buffer.empty[Proc]
  def spawned: List[(List[String], String)] = procs.toList.reverse.map(p => (p.command, p.dir))
  def envs: List[Map[String, String]] = procs.toList.reverse.map(_.env)
  def last: Proc = procs.last
  def sent: Seq[String] = procs.headOption.fold(Seq.empty[String])(_.sent.toSeq)
  def onLine(l: String): Unit = procs.head.onLine(l)
  var runAnswer: List[String] => (Int, String) = _ => (0, "")
  val timers = mutable.Buffer.empty[() => Unit]
  private var n = 0L

  private def now[X](x: => X): Future[X] = Future.fromTry(scala.util.Try(x))
  val ports: World.Ports = World.Ports(
    files = new Port[Files]:
      def apply[X](op: Files[X]): Future[X] = now[X](op match
        case Files.Home() => "/h"
        case Files.Read(p) => files.get(p).orElse(saved.get(p))
        case Files.Write(p, t, own) => if own then files(p) = t else saved(p) = t
        case Files.Append(p, t) => files(p) = files.getOrElse(p, "") + t
        case Files.Remove(p) => files.remove(p); ()
        case Files.Entries(d) => dirs(d) ++ (if d == "/h/.okay-remote/sessions" then
            files.keys.filter(_.startsWith(d + "/")).map(_.drop(d.length + 1)).toList else Nil)
        case Files.Real(p) => p),
    processes = new Port[Processes]:
      def apply[X](op: Processes[X]): Future[X] = now[X](op match
        case Processes.Spawn(c, d, e) => val p = Proc(c, d, e); procs += p; p.agent
        case Processes.Run(c, _, _) => runAnswer(c)
        case Processes.OnPath(name) => has(name)),
    pages = new Port[Pages]:
      def apply[X](op: Pages[X]): Future[X] = now[X](op match
        case Pages.Tell(e, d) => told += e -> d; ()),
    clock = new Port[Clock]:
      def apply[X](op: Clock[X]): Future[X] = op match
        case Clock.Now() => now(1000L)
        case Clock.Sleep(_) =>
          val p = Promise[Unit]()
          timers += (() => { val _ = p.trySuccess(()) })
          p.future,
    random = new Port[Random]:
      def apply[X](op: Random[X]): Future[X] = now[X](op match
        case Random.NextLong() => n += 1; n),
    log = new Port[Log]:
      def apply[X](op: Log[X]): Future[X] = now[X](op match
        case Log.Entry(_, m) => logged += m; ()),
    ec = ExecutionContext.parasitic, time = () => 1000L)

  /** a program's value, run here (every port answers at once) */
  def value[A](p: okay.scala3.![A, World]): A = World.run(ports)(p).value.get.get
