package okay.remote

import okay.effects.json.{Descent, Json}
import scala.collection.mutable

/** THE MACHINE, AS THE REMOTE ASKS IT: its files, its time, a process in a directory. The JVM's in `daemon/`; a test's
 * in memory. Every callback on one thread */
trait Machine:
  def now: Long
  def later(ms: Int)(f: => Unit): Unit
  def randomHex(bytes: Int): String
  def home: String
  def read(file: String): Option[String]
  def write(file: String, text: String): Unit
  def append(file: String, text: String): Unit
  def remove(file: String): Unit
  /** a directory's entries' names; none if it is not one */
  def list(dir: String): List[String]
  def spawn(command: List[String], dir: String): Agent
  def log(text: String): Unit

/** an agent's process: a line to it, its lines, its end */
trait Agent:
  def send(line: String): Unit
  def lines(f: String => Unit): Unit
  def ended(f: String => Unit): Unit
  def kill(): Unit

/** a session as the list shows it */
final case class Session(id: String, project: String, model: String, title: String, agent: String = "",
  created: Long = 0, updated: Long = 0, running: Boolean = false, busy: Boolean = false, waiting: Int = 0):
  def toJson: Json = Json.Obj(Vector("id" -> Json.Str(id), "project" -> Json.Str(project),
    "model" -> Json.Str(model), "title" -> Json.Str(title), "agent" -> Json.Str(agent),
    "created" -> Json.num(created), "updated" -> Json.num(updated), "running" -> Json.Bool(running),
    "busy" -> Json.Bool(busy), "waiting" -> Json.num(waiting)))

object Session:
  import J.*
  def fromJson(j: Json): Option[Session] = str(j, "id").map(id => Session(id, str(j, "project").getOrElse(""),
    str(j, "model").getOrElse(""), str(j, "title").getOrElse(""), str(j, "agent").getOrElse(""),
    long(j, "created"), long(j, "updated"), bool(j, "running"), bool(j, "busy"), long(j, "waiting").toInt))

/** THE MODELS A SESSION MAY RUN ON: Claude's, and the local ones through rozum */
object Models:
  val all: List[(String, String)] = List(
    "claude:sonnet" -> "Claude Sonnet", "claude:opus" -> "Claude Opus", "claude:haiku" -> "Claude Haiku",
    "local:mlx-community:Qwen3.6-35B-A3B-4bit" -> "Local · Qwen3.6 35B (rozum)",
    "local:mlx-community:Qwen3.5-4B-MLX-4bit" -> "Local · Qwen3.5 4B (rozum)")

  /** the command of a session's agent: Claude Code, steered by lines of JSON, every tool call through the hook */
  def command(model: String, settings: String, resume: String): List[String] =
    val claude = List("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
      "--permission-mode", "default", "--settings", settings) ++
      (if resume.nonEmpty then List("--resume", resume) else Nil)
    if model.startsWith("local:") then
      List("rozum", "launch", "--offline", "--model", model.stripPrefix("local:"), "--") ++ claude
    else claude ++ List("--model", model.stripPrefix("claude:"))

/**
 * THE REMOTE: the projects, the sessions, each session's agent and its steps, the policy deciding every tool call,
 * the calls that wait for a person. Over a `Machine`; the page's acts in (`act`), the agent's hook in (`hook`), the
 * page told by `tell` (an event's name, its data).
 */
final class Remote(m: Machine, port: () => Int, tell: (String, Json) => Unit,
                   judgeOf: String => Either[String, Judge]):
  import J.*
  val base: String = s"${m.home}/.okay-remote"
  private def file(s: String) = s"$base/$s"
  private val sessions = mutable.LinkedHashMap.empty[String, Session]
  private val kept = mutable.Map.empty[String, Vector[Step]]
  private final case class Live(agent: Agent, key: String)
  private val live = mutable.Map.empty[String, Live]
  private final case class Wait(session: String, id: String, tool: String, input: Json, why: String,
    answer: (String, String) => Unit)
  private val waits = mutable.LinkedHashMap.empty[String, Wait]
  private var judge: Either[String, Judge] = Left("not read yet")
  val askFor: Int = 10 * 60 * 1000

  def policy: String = m.read(file("policy.policy")).getOrElse(Defaults.policy)

  def start(): Unit =
    judge = judgeOf(policy)
    judge.left.foreach(e => m.log(s"okay-remote: the policy does not compile, every call is refused: $e\n"))
    m.list(file("sessions")).filter(_.endsWith(".json")).flatMap(n => m.read(file(s"sessions/$n")))
      .flatMap(t => Descent.parse(t).toOption).flatMap(Session.fromJson).sortBy(_.updated)
      .foreach(s => sessions(s.id) = s.copy(running = false, busy = false, waiting = 0))

  // ---- THE PROJECTS: the directories with `.git` under the roots (`~/.okay-remote/roots`, a line each)

  def roots: List[String] = m.read(file("roots")).map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList)
    .filter(_.nonEmpty).getOrElse(List(s"${m.home}/work"))
  def projects: List[String] =
    def git(d: String) = m.list(d).contains(".git")
    def under(d: String, depth: Int): List[String] =
      if git(d) then List(d)
      else if depth == 0 then Nil
      else m.list(d).filterNot(_.startsWith(".")).sorted.flatMap(n => under(s"$d/$n", depth - 1))
    roots.flatMap(r => under(r, 2)).distinct

  // ---- WHAT THE PAGE ASKS

  def snapshot: Json = Json.Obj(Vector(
    "projects" -> Json.Arr(projects.map(Json.Str(_)).toVector),
    "sessions" -> Json.Arr(sessions.values.toVector.reverse.map(s => summary(s).toJson)),
    "models" -> Json.Arr(Models.all.map((k, l) => Json.obj("id" -> Json.Str(k), "label" -> Json.Str(l))).toVector),
    "policy" -> Json.Str(policy), "policyError" -> Json.Str(judge.left.getOrElse(""))))

  def act(body: Json): Either[String, Json] =
    def s(k: String) = str(body, k).getOrElse("")
    str(body, "type").getOrElse("") match
      case "hello" => Right(snapshot)
      case "open" => sessions.get(s("session")).toRight("no such session").map(x => Json.Obj(Vector(
          "session" -> summary(x).toJson,
          "steps" -> Json.Arr(steps(x.id).map(Step.toJson)),
          "waiting" -> Json.Arr(waits.values.filter(_.session == x.id).map(waitJson).toVector))))
      case "new" =>
        if !projects.contains(s("project")) then Left("not a project here")
        else if s("task").trim.isEmpty then Left("say what to do")
        else
          val id = m.randomHex(6)
          val model = if Models.all.exists(_._1 == s("model")) then s("model") else Models.all.head._1
          val x = Session(id, s("project"), model, s("task").linesIterator.next().take(80), created = m.now,
            updated = m.now)
          sessions(id) = x
          kept(id) = Vector.empty
          say(id, s("task")).map(_ => summary(sessions(id)).toJson)
      case "say" => say(s("session"), s("text")).map(_ => Json.Obj.empty)
      case "stop" => live.remove(s("session")).fold(Left("not running")) { l =>
          l.agent.kill(); Right(Json.Obj.empty)
        }
      case "verdict" => waits.remove(s("call")).fold(Left("nothing waits for that")) { w =>
          val approve = bool(body, "approve")
          val why = s("why")
          record(w.session, Step.Decided(w.id, if approve then "approved" else "refused", why))
          w.answer(if approve then "allow" else "deny", if approve then "approved on the phone"
            else if why.nonEmpty then s"refused: $why" else "refused by the person")
          changed(w.session)
          Right(Json.Obj.empty)
        }
      case "policy" =>
        judgeOf(s("text")) match
          case Left(e) => Left(e)
          case Right(j) =>
            m.write(file("policy.policy"), s("text")); judge = Right(j); tell("policy", Json.Str(s("text")))
            Right(Json.Obj.empty)
      case "forget" => sessions.get(s("session")).fold(Left("no such session")) { x =>
          live.remove(x.id).foreach(_.agent.kill())
          sessions.remove(x.id); kept.remove(x.id)
          m.remove(file(s"sessions/${x.id}.json")); m.remove(file(s"sessions/${x.id}.jsonl"))
          tell("forgot", Json.Str(x.id))
          Right(Json.Obj.empty)
        }
      case other => Left(s"no act $other")

  /** a person's words to a session: to its agent, started (or started again, resumed) if it is not running */
  private def say(id: String, text: String): Either[String, Unit] =
    if text.trim.isEmpty then Left("say something")
    else sessions.get(id).toRight("no such session").map { x =>
      if !live.contains(id) then run(x)
      record(id, Step.You(text))
      live.get(id).foreach(_.agent.send(Json.print(Json.Obj(Vector("type" -> Json.Str("user"),
        "message" -> Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(text)))))))
      sessions(id) = sessions(id).copy(busy = true, updated = m.now)
      changed(id)
    }

  private def run(x: Session): Unit =
    val key = m.randomHex(16)
    // every tool call through the hook; the daemon gone, the call is not done (exit 2: refused, the agent told)
    val hook = s"curl -sf --max-time ${askFor / 1000 + 60} -X POST -H content-type:application/json " +
      s"--data-binary @- http://127.0.0.1:${port()}/hook/${x.id}?k=$key || " +
      "{ echo 'okay-remote is not reachable: the call is not done' >&2; exit 2; }"
    val settings = Json.print(Json.obj("hooks" -> Json.obj("PreToolUse" -> Json.arr(Json.obj(
      "matcher" -> Json.Str("*"), "hooks" -> Json.arr(Json.obj("type" -> Json.Str("command"),
        "command" -> Json.Str(hook), "timeout" -> Json.num(askFor / 1000 + 60))))))))
    val agent = m.spawn(Models.command(x.model, settings, x.agent), x.project)
    live(x.id) = Live(agent, key)
    sessions(x.id) = x.copy(running = true)
    agent.lines(l => Step.ofAgent(l).foreach { st =>
      st match
        case Step.Began(a, _) if a.nonEmpty && a != sessions(x.id).agent =>
          sessions(x.id) = sessions(x.id).copy(agent = a); save(x.id)
        case Step.Ended(_, _, _, _, _) => sessions(x.id) = sessions(x.id).copy(busy = false); changed(x.id)
        case _ => ()
      record(x.id, st)
    })
    agent.ended { why =>
      if live.get(x.id).exists(_.agent eq agent) then live.remove(x.id)
      waits.filter(_._2.session == x.id).keys.toList.foreach(k => waits.remove(k).foreach(_.answer("deny", "ended")))
      if sessions.contains(x.id) then
        record(x.id, Step.Stopped(why))
        sessions(x.id) = sessions(x.id).copy(running = false, busy = false)
        changed(x.id)
    }

  // ---- THE HOOK: a tool call the agent is about to make, decided

  /** Claude Code's PreToolUse hook's input: the answer is the hook's output (JSON), or a status and why */
  def hook(id: String, key: String, body: Json)(answer: Either[(Int, String), Json] => Unit): Unit =
    if !live.get(id).exists(l => Secret.same(l.key, key)) then answer(Left((403, "not this session's")))
    else
      val call = str(body, "tool_use_id").getOrElse(m.randomHex(8))
      val tool = str(body, "tool_name").getOrElse("?")
      val input = get(body, "tool_input").getOrElse(Json.Obj.empty)
      def out(verdict: String, why: String) = answer(Right(Json.obj("hookSpecificOutput" -> Json.Obj(Vector(
        "hookEventName" -> Json.Str("PreToolUse"), "permissionDecision" -> Json.Str(verdict),
        "permissionDecisionReason" -> Json.Str(if why.nonEmpty then why else s"okay-remote: $verdict"))))))
      judge match
        case Left(e) => record(id, Step.Decided(call, "deny", s"the policy does not compile: $e"))
          out("deny", s"the policy does not compile: $e")
        case Right(j) => j.decide(call, tool, input, sessions(id).project) { (verdict, why) =>
            record(id, Step.Decided(call, verdict, why))
            if verdict != "ask" then out(verdict, why)
            else
              val w = Wait(id, call, tool, input, why, out)
              waits(call) = w
              tell("waiting", waitJson(w))
              changed(id)
              m.later(askFor)(waits.remove(call).foreach { w =>
                record(id, Step.Decided(call, "timed out", ""))
                w.answer("deny", "no one answered in time")
                changed(id)
              })
          }

  // ---- KEEPING AND TELLING

  private def waitJson(w: Wait): Json = Json.Obj(Vector("session" -> Json.Str(w.session), "call" -> Json.Str(w.id),
    "tool" -> Json.Str(w.tool), "input" -> w.input, "why" -> Json.Str(w.why)))

  private def summary(x: Session): Session = x.copy(running = live.contains(x.id),
    waiting = waits.values.count(_.session == x.id))

  private def record(id: String, st: Step): Unit =
    kept(id) = steps(id) :+ st
    m.append(file(s"sessions/$id.jsonl"), Json.print(Step.toJson(st)) + "\n")
    tell("step", Json.obj("session" -> Json.Str(id), "step" -> Step.toJson(st)))

  private def loadSteps(id: String): Vector[Step] = m.read(file(s"sessions/$id.jsonl")).toVector
    .flatMap(_.linesIterator).flatMap(l => Descent.parse(l).toOption).flatMap(Step.fromJson)

  private def save(id: String): Unit =
    sessions.get(id).foreach(x => m.write(file(s"sessions/$id.json"), Json.print(x.toJson)))

  private def changed(id: String): Unit =
    save(id)
    sessions.get(id).foreach(x => tell("session", summary(x).toJson))

  // the steps of the sessions read when first asked
  private def steps(id: String): Vector[Step] = kept.getOrElseUpdate(id, loadSteps(id))

/** comparing secrets in a time that does not tell how much of them matched */
object Secret:
  def same(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0
