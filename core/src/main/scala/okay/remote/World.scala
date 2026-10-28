package okay.remote

import okay.effects.json.Json
import okay.scala3.*
import okay.scala3.std.*
import scala.concurrent.{ExecutionContext, Future}

/** THE MACHINE'S FILES, as the remote asks them: its home, a file read, written (readable by its owner alone, or as
 * files are: an agent's work), appended to, removed, a directory's entries, a path as the system names it */
enum Files[X]:
  case Home() extends Files[String]
  case Read(path: String) extends Files[Option[String]]
  case Write(path: String, text: String, own: Boolean) extends Files[Unit]
  case Append(path: String, text: String) extends Files[Unit]
  case Remove(path: String) extends Files[Unit]
  case Entries(dir: String) extends Files[List[String]]
  case Real(path: String) extends Files[String]

object Files:
  def home[R[_]](using In[Files, R]): String ! R = perform[Files](Home())
  def read[R[_]](path: String)(using In[Files, R]): Option[String] ! R = perform[Files](Read(path))
  /** written readable by its owner alone: the remote's own files */
  def keep[R[_]](path: String, text: String)(using In[Files, R]): Unit ! R = perform[Files](Write(path, text, true))
  /** written as files are: a project's, an agent's work */
  def save[R[_]](path: String, text: String)(using In[Files, R]): Unit ! R = perform[Files](Write(path, text, false))
  def append[R[_]](path: String, text: String)(using In[Files, R]): Unit ! R = perform[Files](Append(path, text))
  def remove[R[_]](path: String)(using In[Files, R]): Unit ! R = perform[Files](Remove(path))
  def entries[R[_]](dir: String)(using In[Files, R]): List[String] ! R = perform[Files](Entries(dir))
  def real[R[_]](path: String)(using In[Files, R]): String ! R = perform[Files](Real(path))

/** THE MACHINE'S PROCESSES: one started in a directory with what it adds to the environment (an agent, kept running);
 * a command run to its end (its exit code, its output); whether a command is on the PATH */
enum Processes[X]:
  case Spawn(command: List[String], dir: String, env: Map[String, String]) extends Processes[Agent]
  case Run(command: List[String], dir: String, limit: Int) extends Processes[(Int, String)]
  case OnPath(name: String) extends Processes[Boolean]

object Processes:
  def spawn[R[_]](command: List[String], dir: String, env: Map[String, String] = Map.empty)(
    using In[Processes, R]): Agent ! R = perform[Processes](Spawn(command, dir, env))
  def run[R[_]](command: List[String], dir: String, limit: Int = 400000)(using In[Processes, R]): (Int, String) ! R =
    perform[Processes](Run(command, dir, limit))
  def onPath[R[_]](name: String)(using In[Processes, R]): Boolean ! R = perform[Processes](OnPath(name))

/** THE PAGES: an event told to every page open (and every phone) */
enum Pages[X]:
  case Tell(event: String, data: Json) extends Pages[Unit]

object Pages:
  def tell[R[_]](event: String, data: Json)(using In[Pages, R]): Unit ! R = perform[Pages](Tell(event, data))

/** WHAT A PLACE DOES FOR ONE EFFECT: each operation, as a future (on the place's one thread) */
trait Port[E[_]]:
  def apply[X](op: E[X]): Future[X]

/** THE REMOTE'S WORLD: its effects, and okay-scala's time, randomness, log, and waiting */
type World[X] = (Async + Files + Processes + Pages + Clock + Random + Log)[X]

object World:
  /** a place: one port an effect (the JVM's in `daemon/`, a test's in memory) */
  final case class Ports(files: Port[Files], processes: Port[Processes], pages: Port[Pages], clock: Port[Clock],
                         random: Port[Random], log: Port[Log], ec: ExecutionContext, time: () => Long)
  /** `time`: the place's clock as okay-guard's gate asks it (its program is okay-guard's, not this one) */

  private def by[E[_], R[_], Rest[_], A](port: Port[E])(p: A ! R)(using Split[E, R, Rest], In[Async, Rest]): A ! Rest =
    handle(p, Handler.general[E, Rest, A, A](pure)(
      [X] => (op: E[X], k: X => A ! Rest) => Async.fromFuture[X, Rest](port(op)).flatMap(k)))

  /** a program of the remote, run in a place: what it ends with, when it ends */
  def run[A](ports: Ports)(p: A ! World): Future[A] =
    given ExecutionContext = ports.ec
    Async.toFuture(by(ports.log)(by(ports.random)(by(ports.clock)(by(ports.pages)(by(ports.processes)(
      by(ports.files)(p)))))))
