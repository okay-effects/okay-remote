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
  /** written readable by its owner alone (the daemon's own files) */
  def write(file: String, text: String): Unit
  /** a project's file written as files are (an agent's work) */
  def save(file: String, text: String): Unit
  /** a command of this name on the PATH */
  def onPath(name: String): Boolean
  def append(file: String, text: String): Unit
  def remove(file: String): Unit
  /** a directory's entries' names; none if it is not one */
  def list(dir: String): List[String]
  /** a path as the system names it, its links followed (`/tmp` is `/private/tmp` on a Mac) */
  def real(path: String): String
  /** a process in `dir`, with `env` added to the daemon's own */
  def spawn(command: List[String], dir: String, env: Map[String, String] = Map.empty): Agent
  /** a command run to its end: its exit code and its output (at most `limit` characters) */
  def run(command: List[String], dir: String, limit: Int = 400000)(done: (Int, String) => Unit): Unit
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
  /** what a session may run on here: Claude Code with Claude or a local model, and the ACP agents installed */
  def here(m: Machine): List[(String, String)] =
    (if m.onPath("claude") then all else Nil) ++
      Agents.all.filter(_.needs.forall(m.onPath)).map(a => s"acp:${a.id}" -> a.label)
  /** what goes through the policy for a session on it */
  def through(model: String): String =
    Agents.of(model).fold("every tool call (Claude Code's hook)")(_.through)
  val all: List[(String, String)] = List(
    "claude:sonnet" -> "Claude Sonnet", "claude:opus" -> "Claude Opus", "claude:haiku" -> "Claude Haiku",
    "local:mlx-community:Qwen3.6-35B-A3B-4bit" -> "Local · Qwen3.6 35B (rozum)",
    "local:mlx-community:Qwen3.5-4B-MLX-4bit" -> "Local · Qwen3.5 4B (rozum)")

  /** the command of a session's agent and what it adds to the environment: Claude Code, steered by lines of JSON,
   * every tool call through the hook; in rozum's jail where it can be (specs/remote.md, "Safety, in layers") */
  def command(model: String, settings: String, resume: String, jail: Jail): (List[String], Map[String, String]) =
    val claude = List("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
      "--permission-mode", "default", "--settings", settings) ++
      (if resume.nonEmpty then List("--resume", resume) else Nil)
    if model.startsWith("local:") then
      (List("rozum", "launch", "--offline", "--model", model.stripPrefix("local:"), "--") ++ claude, Map.empty)
    else
      val withModel = claude ++ List("--model", model.stripPrefix("claude:"))
      jail.claudeToken match
        case Some(token) if jail.rozum => (List("rozum", "launch", "--no-model", "--") ++ withModel,
            Map("CLAUDE_CODE_OAUTH_TOKEN" -> token, "ROZUM_SANDBOX_NETWORK" -> "full"))
        case _ => (withModel, Map.empty)

/** what the machine has for the jail: rozum, and a token for Claude that needs no keychain */
final case class Jail(rozum: Boolean = false, claudeToken: Option[String] = None):
  def claudeJailed: Boolean = rozum && claudeToken.nonEmpty

/**
 * THE REMOTE: the projects, the sessions, each session's agent and its steps, the policy deciding every tool call,
 * the calls that wait for a person. Over a `Machine`; the page's acts in (`act`), the agent's hook in (`hook`), the
 * page told by `tell` (an event's name, its data).
 */
final class Remote(m: Machine, port: () => Int, tell: (String, Json) => Unit,
                   judgeOf: String => Either[String, Judge], jail: () => Jail = () => Jail()):
  import J.*
  val base: String = s"${m.home}/.okay-remote"
  private def file(s: String) = s"$base/$s"
  private val sessions = mutable.LinkedHashMap.empty[String, Session]
  private val kept = mutable.Map.empty[String, Vector[Step]]
  private final case class Live(agent: Agent, key: String, acp: Option[AcpSession] = None)
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
    roots.flatMap(r => under(m.real(r), 2)).distinct

  // ---- WHAT THE PAGE ASKS

  def snapshot: Json = Json.Obj(Vector(
    "projects" -> Json.Arr(projects.map(Json.Str(_)).toVector),
    "sessions" -> Json.Arr(sessions.values.toVector.reverse.map(s => summary(s).toJson)),
    "models" -> Json.Arr(Models.here(m).map((k, l) => Json.obj("id" -> Json.Str(k), "label" -> Json.Str(l),
      "through" -> Json.Str(Models.through(k)))).toVector),
    "policy" -> Json.Str(policy), "policyError" -> Json.Str(judge.left.getOrElse("")),
    "jail" -> Json.obj("rozum" -> Json.Bool(jail().rozum), "claude" -> Json.Bool(jail().claudeJailed))))

  /** an act of the page, answered when it is done (most at once; `diff` when git has answered) */
  def act(body: Json)(answer: Either[String, Json] => Unit): Unit =
    def s(k: String) = str(body, k).getOrElse("")
    str(body, "type").getOrElse("") match
      case "diff" => diff(s("session"))(answer)
      case "always" => always(s("call"))(answer)
      case _ => answer(now(body))

  private def now(body: Json): Either[String, Json] =
    def s(k: String) = str(body, k).getOrElse("")
    str(body, "type").getOrElse("") match
      case "hello" => Right(snapshot)
      case "open" => sessions.get(s("session")).toRight("no such session").map(x => Json.Obj(Vector(
          "session" -> summary(x).toJson,
          "steps" -> Json.Arr(steps(x.id).map(Step.toJson)),
          "waiting" -> Json.Arr(waits.values.filter(_.session == x.id).map(waitJson).toVector))))
      case "new" =>
        if !projects.contains(s("project")) then Left("not a project here")
        else if Models.here(m).isEmpty then Left("no coding agent is installed here")
        else if s("task").trim.isEmpty then Left("say what to do")
        else
          val id = m.randomHex(6)
          val offered = Models.here(m)
          val model = if offered.exists(_._1 == s("model")) then s("model") else offered.headOption.fold("")(_._1)
          val line = s("task").trim.linesIterator.next()
          val title = if line.length <= 80 then line
            else line.take(79).reverse.dropWhile(_ != ' ').reverse.trim + " …"
          val x = Session(id, s("project"), model, title, created = m.now,
            updated = m.now)
          sessions(id) = x
          kept(id) = Vector.empty
          say(id, s("task")).map(_ => summary(sessions(id)).toJson)
      case "say" => say(s("session"), s("text")).map(_ => Json.Obj.empty)
      case "stop" => live.remove(s("session")).fold(Left("not running")) { l =>
          l.acp.foreach(_.cancel())
          l.agent.kill(); Right(Json.Obj.empty)
        }
      case "verdict" => verdict(s("call"), bool(body, "approve"), s("why"))
      case "policy" =>
        judgeOf(s("text")) match
          case Left(e) => Left(e)
          case Right(j) =>
            m.write(file("policy.policy"), s("text")); judge = Right(j); tell("policy", Json.Str(s("text")))
            Right(Json.Obj.empty)
      case "forget" => sessions.get(s("session")).fold(Left("no such session")) { x =>
          live.remove(x.id).foreach(_.agent.kill())
          sessions.remove(x.id); kept.remove(x.id); heads.remove(x.id)
          m.remove(file(s"sessions/${x.id}.json")); m.remove(file(s"sessions/${x.id}.jsonl"))
          tell("forgot", Json.Str(x.id))
          Right(Json.Obj.empty)
        }
      case other => Left(s"no act $other")

  private def verdict(call: String, approve: Boolean, why: String): Either[String, Json] =
    waits.remove(call).fold(Left("nothing waits for that")) { w =>
      record(w.session, Step.Decided(w.id, if approve then "approved" else "refused", why))
      w.answer(if approve then "allow" else "deny", if approve then "approved on the phone"
        else if why.nonEmpty then s"refused: $why" else "refused by the person")
      changed(w.session)
      Right(Json.Obj.empty)
    }

  /** ALWAYS ALLOW: a rule made of a waiting call, added to the policy if, added, it allows this call — then the call
   * approved. An `ask` still wins over an `allow`: then nothing is added, and the answer says which rule asks */
  private def always(call: String)(answer: Either[String, Json] => Unit): Unit =
    waits.get(call) match
      case None => answer(Left("nothing waits for that"))
      case Some(w) =>
        val text = Rules.added(policy, w.tool, w.input)
        judgeOf(text) match
          case Left(e) => answer(Left(s"the rule did not compile: $e"))
          case Right(j) => j.decide(call, w.tool, w.input, sessions.get(w.session).fold("")(_.project)) {
              (v, why) =>
                if v != "allow" then answer(Left(s"a rule still ${if v == "deny" then "denies" else "asks for"} it" +
                  (if why.nonEmpty then s": $why" else "") + " — change the policy to allow it"))
                else
                  m.write(file("policy.policy"), text); judge = Right(j); tell("policy", Json.Str(text))
                  answer(verdict(call, true, "always allowed").map(_ => Json.obj("policy" -> Json.Str(text))))
            }

  /** CHANGES: the project's `git status` and `git diff` (staged and not), as git says them */
  private def diff(id: String)(answer: Either[String, Json] => Unit): Unit =
    sessions.get(id) match
      case None => answer(Left("no such session"))
      case Some(x) =>
        m.run(List("git", "status", "--short"), x.project) { (c1, status) =>
          if c1 != 0 then answer(Left(s"git status: ${status.take(300)}"))
          else m.run(List("git", "diff", "HEAD", "--no-color", "--no-ext-diff"), x.project) { (c2, d) =>
            m.run(List("git", "diff", "--no-color", "--no-ext-diff"), x.project) { (_, unborn) =>
              // a repository with no commit yet has no HEAD: the working tree's diff then
              answer(Right(Json.obj("status" -> Json.Str(status), "diff" -> Json.Str(if c2 == 0 then d else unborn))))
            }
          }
        }

  /** a person's words to a session: to its agent, started (or started again, resumed) if it is not running */
  private def say(id: String, text: String): Either[String, Unit] =
    if text.trim.isEmpty then Left("say something")
    else sessions.get(id).toRight("no such session").map { x =>
      if !live.contains(id) then run(x)
      record(id, Step.You(text))
      live.get(id).foreach(l => l.acp match
        case Some(a) => a.prompt(text)
        case None => l.agent.send(Json.print(Json.Obj(Vector("type" -> Json.Str("user"),
            "message" -> Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(text)))))))
      sessions(id) = sessions(id).copy(busy = true, updated = m.now)
      changed(id)
    }

  private def run(x: Session): Unit = Agents.of(x.model) match
    case Some(kind) => runAcp(x, kind)
    case None => runClaude(x)

  /** an agent over ACP: started in the project, its files and commands through the gate, its updates the steps */
  private def runAcp(x: Session, kind: AgentKind): Unit =
    val agent = m.spawn(kind.command, x.project)
    // the agent's commands, run here: in rozum's jail where there is one (writes in the project only, no secrets)
    def command(cmd: String, dir: String): Agent =
      val sh = List("/bin/sh", "-c", cmd + " 2>&1")
      m.spawn(if jail().rozum then List("rozum", "launch", "--no-model", "--") ++ sh else sh, dir)
    val acp = AcpSession(m, agent, kind, x.project, x.agent, (call, tool, input) => gate(x.id, call, tool, input),
      st => stepped(x.id, st), command)
    live(x.id) = Live(agent, m.randomHex(16), Some(acp))
    sessions(x.id) = x.copy(running = true)
    ended(x.id, agent)
    acp.start()

  /** a step of a session's agent: kept, told; its start names the agent's session (to go on with later) */
  private def stepped(id: String, st: Step): Unit =
    st match
      case Step.Began(a, _) if a.nonEmpty && sessions.get(id).exists(_.agent != a) =>
        sessions(id) = sessions(id).copy(agent = a); save(id)
      case Step.Ended(_, _, _, _, _) => sessions.get(id).foreach(x => sessions(id) = x.copy(busy = false))
        changed(id)
      case _ => ()
    if sessions.contains(id) then record(id, st)

  private def ended(id: String, agent: Agent): Unit =
    agent.ended { why =>
      if live.get(id).exists(_.agent eq agent) then live.remove(id)
      waits.filter(_._2.session == id).keys.toList.foreach(k => waits.remove(k).foreach(_.answer("deny", "ended")))
      if sessions.contains(id) then
        record(id, Step.Stopped(why))
        sessions(id) = sessions(id).copy(running = false, busy = false)
        changed(id)
    }

  private def runClaude(x: Session): Unit =
    val key = m.randomHex(16)
    // every tool call through the hook; the daemon gone, the call is not done (exit 2: refused, the agent told)
    val hook = s"curl -sf --max-time ${askFor / 1000 + 60} -X POST -H content-type:application/json " +
      s"--data-binary @- http://127.0.0.1:${port()}/hook/${x.id}?k=$key || " +
      "{ echo 'okay-remote is not reachable: the call is not done' >&2; exit 2; }"
    val settings = Json.print(Json.obj("hooks" -> Json.obj("PreToolUse" -> Json.arr(Json.obj(
      "matcher" -> Json.Str("*"), "hooks" -> Json.arr(Json.obj("type" -> Json.Str("command"),
        "command" -> Json.Str(hook), "timeout" -> Json.num(askFor / 1000 + 60))))))))
    val (command, env) = Models.command(x.model, settings, x.agent, jail())
    val agent = m.spawn(command, x.project, env)
    live(x.id) = Live(agent, key)
    sessions(x.id) = x.copy(running = true)
    agent.lines(l => Step.ofAgent(l).foreach(stepped(x.id, _)))
    ended(x.id, agent)

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
      gate(id, call, tool, input)(out)

  /** THE GATE: a call decided by the policy — allowed or denied at once, or asked of a person (the phone) and
   * answered when they answer, or refused when the time is up. Every decision a step of the session */
  def gate(id: String, call: String, tool: String, input: Json)(answer: (String, String) => Unit): Unit =
    judge match
      case Left(e) => record(id, Step.Decided(call, "deny", s"the policy does not compile: $e"))
        answer("deny", s"the policy does not compile: $e")
      case Right(j) => j.decide(call, tool, input, sessions.get(id).fold("")(_.project)) { (verdict, why) =>
          record(id, Step.Decided(call, verdict, why))
          if verdict != "ask" then answer(verdict, why)
          else
            val w = Wait(id, call, tool, input, why, answer)
            waits(call) = w
            tell("waiting", waitJson(w))
            changed(id)
            m.later(askFor)(waits.remove(call).foreach { w =>
              record(id, Step.Decided(call, "timed out", ""))
              w.answer("deny", "no one answered in time")
              changed(id)
            })
        }

  /** each session's record checked: its chain unbroken, or where it breaks */
  def verify: List[(String, Either[String, Int])] = sessions.keys.toList.sorted.map { id =>
    val records = m.read(file(s"sessions/$id.jsonl")).toList.flatMap(_.linesIterator)
      .flatMap(l => Descent.parse(l).toOption)
    id -> okay.guard.Chain.verify(records)
  }

  // ---- KEEPING AND TELLING

  private def waitJson(w: Wait): Json = Json.Obj(Vector("session" -> Json.Str(w.session), "call" -> Json.Str(w.id),
    "tool" -> Json.Str(w.tool), "input" -> w.input, "why" -> Json.Str(w.why)))

  private def summary(x: Session): Session = x.copy(running = live.contains(x.id),
    waiting = waits.values.count(_.session == x.id))

  /** the last link of each session's chain: its sequence number and hash */
  private val heads = mutable.Map.empty[String, (Long, String)]
  private def head(id: String): (Long, String) = heads.getOrElseUpdate(id,
    m.read(file(s"sessions/$id.jsonl")).flatMap(_.linesIterator.toList.lastOption)
      .flatMap(l => Descent.parse(l).toOption)
      .fold((0L, okay.guard.Chain.start))(j => (okay.guard.Chain.seqOf(j), okay.guard.Chain.hashOf(j))))

  private def record(id: String, st: Step): Unit =
    kept(id) = steps(id) :+ st
    val (seq, prev) = head(id)
    val linked = okay.guard.Chain.link(seq + 1, prev, Step.toJson(st))
    heads(id) = (seq + 1, okay.guard.Chain.hashOf(linked))
    m.append(file(s"sessions/$id.jsonl"), Json.print(linked) + "\n")
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
