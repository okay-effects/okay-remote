package okay.remote

import okay.effects.json.{Descent, Json}
import okay.guard.{Action, Chain, Decision, Entry, Gate, Judged, Judging, Ledger, Outcome, Verdict}
import okay.guard.acp.{AcpClient, AgentKind, Agents, Event, Host, Line}
import okay.scala3.*
import okay.scala3.std.{Async, Clock, Level, Log, Random}
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

/** THE AUDIT JOURNAL, as okay-guard keeps it (its `Ledger`'s records, chained): a record written; a policy's text kept
 * once by its hash */
trait Audit:
  def record(r: Json): Unit
  def policy(hash: String, text: String): Unit
object Audit:
  val none: Audit = new Audit:
    def record(r: Json): Unit = ()
    def policy(hash: String, text: String): Unit = ()

/** an agent's process: a line to it, its lines, its end (okay-guard-acp's `Line`) */
trait Agent extends Line

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
  def here[R[_]](using In[Processes, R]): List[(String, String)] ! R =
    val wanted = ("claude" :: Agents.all.flatMap(_.needs)).distinct
    wanted.foldLeft(pure[Map[String, Boolean], R](Map.empty))((acc, n) =>
      acc.flatMap(m => Processes.onPath[R](n).map(b => m + (n -> b)))).map(has =>
      (if has("claude") then all else Nil) ++
        Agents.all.filter(_.needs.forall(has)).map(a => s"acp:${a.id}" -> a.label))
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
 * the calls that wait for a person. Its work is programs over its `World` — the machine's files and processes, the
 * pages, the time, randomness, the log — run in a place by `World.run` (the JVM's ports in `daemon/`, a test's in
 * memory); its own memory is this object's. The page's acts in (`act`), the agent's hook in (`hook`), every answer
 * given when its program ends.
 */
final class Remote(ports: World.Ports, port: () => Int, judgeOf: String => Either[String, Judge],
                   audit: Audit = Audit.none):
  import J.*
  type W[X] = World[X]
  private given scala.concurrent.ExecutionContext = ports.ec

  /** a program of the remote run in its place; what it ends with to `done` */
  def go[A](p: A ! W)(done: A => Unit = (_: A) => ()): Unit = World.run(ports)(p).onComplete {
    case Success(a) => done(a)
    case Failure(e) => val _ = ports.log(Log.Entry(Level.Error, s"okay-remote: ${Option(e.getMessage).getOrElse(e)}"))
  }
  /** a program run for what it does: nothing waits for its end */
  private def fire(p: Unit ! W): Unit = go(p)(_ => ())
  private def unit: Unit ! W = pure(())
  private def each[A, B](xs: Iterable[A])(f: A => B ! W): List[B] ! W =
    xs.foldLeft(pure[List[B], W](Nil))((acc, a) => acc.flatMap(l => f(a).map(_ :: l))).map(_.reverse)
  private def all(ps: Iterable[Unit ! W]): Unit ! W = ps.foldLeft(unit)((a, b) => a.flatMap(_ => b))
  private def hex(bytes: Int): String ! W =
    each(0 until (bytes + 7) / 8)(_ => Random.long[W]).map(_.map(n => f"$n%016x").mkString.take(bytes * 2))

  // ---- ITS MEMORY

  var base: String = ""
  private def file(s: String) = s"$base/$s"
  private val sessions = mutable.LinkedHashMap.empty[String, Session]
  private val kept = mutable.Map.empty[String, Vector[Step]]
  /** the last link of each session's chain: its sequence number and hash */
  private val heads = mutable.Map.empty[String, (Long, String)]
  private final case class Live(agent: Agent, key: String, acp: Option[AcpClient] = None)
  private val live = mutable.Map.empty[String, Live]
  private final case class Wait(session: String, id: String, tool: String, input: Json, why: String,
    answer: Verdict => Unit)
  private val waits = mutable.LinkedHashMap.empty[String, Wait]
  private var judge: Either[String, Judge] = Left("not read yet")
  private var policyNow: String = Defaults.policy
  /** when each session's turn began: its end's time is the remote's own */
  private val turns = mutable.Map.empty[String, Long]
  var jail: Jail = Jail()
  val askFor: Int = 10 * 60 * 1000

  /** a session's project and model, by its id (what a notification may say of it) */
  def about(id: String): Option[(String, String)] = sessions.get(id).map(x => (x.project, x.model))
  def policy: String = policyNow

  /** started: its home, the policy, the jail there is, the sessions kept */
  def start(done: () => Unit = () => ()): Unit = go(starting)(_ => done())
  def starting: Unit ! W =
    for
      home <- Files.home[W]
      _ = base = s"$home/.okay-remote"
      text <- Files.read[W](file("policy.policy"))
      _ = { policyNow = text.getOrElse(Defaults.policy); judge = judgeOf(policyNow) }
      _ <- judge.fold(e => Log.warn[W](s"okay-remote: the policy does not compile, every call is refused: $e"),
        _ => unit)
      _ = keepPolicy()
      rozum <- Processes.onPath[W]("rozum")
      token <- Files.read[W](file("claude-token"))
      _ = jail = Jail(rozum, token.map(_.trim).filter(_.nonEmpty))
      names <- Files.entries[W](file("sessions"))
      texts <- each(names.filter(_.endsWith(".json")))(n => Files.read[W](file(s"sessions/$n")))
    yield texts.flatten.flatMap(t => Descent.parse(t).toOption).flatMap(Session.fromJson).sortBy(_.updated)
      .foreach(s => sessions(s.id) = s.copy(running = false, busy = false, waiting = 0))

  // ---- THE PROJECTS: the directories with `.git` under the roots (`~/.okay-remote/roots`, a line each)

  def roots: List[String] ! W = Files.read[W](file("roots")).flatMap(r =>
    r.map(_.linesIterator.map(_.trim).filter(_.nonEmpty).toList).filter(_.nonEmpty) match
      case Some(rs) => pure(rs)
      case None => Files.home[W].map(h => List(s"$h/work")))
  def projects: List[String] ! W =
    def under(d: String, depth: Int): List[String] ! W = Files.entries[W](d).flatMap(names =>
      if names.contains(".git") then pure(List(d))
      else if depth == 0 then pure(Nil)
      else each(names.filterNot(_.startsWith(".")).sorted)(n => under(s"$d/$n", depth - 1)).map(_.flatten))
    roots.flatMap(rs => each(rs)(r => Files.real[W](r).flatMap(under(_, 2)))).map(_.flatten.distinct)

  // ---- WHAT THE PAGE ASKS

  def snapshot: Json ! W =
    for
      ps <- projects
      ms <- Models.here[W]
    yield Json.Obj(Vector(
      "projects" -> Json.Arr(ps.map(Json.Str(_)).toVector),
      "sessions" -> Json.Arr(sessions.values.toVector.reverse.map(s => summary(s).toJson)),
      "models" -> Json.Arr(ms.map((k, l) => Json.obj("id" -> Json.Str(k), "label" -> Json.Str(l),
        "through" -> Json.Str(Models.through(k)))).toVector),
      "policy" -> Json.Str(policyNow), "policyError" -> Json.Str(judge.left.getOrElse("")),
      "jail" -> Json.obj("rozum" -> Json.Bool(jail.rozum), "claude" -> Json.Bool(jail.claudeJailed))))

  /** an act of the page, answered when its program ends */
  def act(body: Json)(answer: Either[String, Json] => Unit): Unit = go(acting(body))(answer)

  def acting(body: Json): Either[String, Json] ! W =
    def s(k: String) = str(body, k).getOrElse("")
    str(body, "type").getOrElse("") match
      case "hello" => snapshot.map(Right(_))
      case "diff" => diff(s("session"))
      case "always" => always(s("call"))
      case "open" => sessions.get(s("session")) match
          case None => pure(Left("no such session"))
          case Some(x) => loaded(x.id).map(_ => Right(Json.Obj(Vector(
              "session" -> summary(x).toJson,
              "steps" -> Json.Arr(kept(x.id).map(Step.toJson)),
              "waiting" -> Json.Arr(waits.values.filter(_.session == x.id).map(waitJson).toVector)))))
      case "new" =>
        for
          ps <- projects
          offered <- Models.here[W]
          id <- hex(6)
          now <- Clock.now[W]
          out <- if !ps.contains(s("project")) then pure(Left("not a project here"))
            else if offered.isEmpty then pure(Left("no coding agent is installed here"))
            else if s("task").trim.isEmpty then pure(Left("say what to do"))
            else
              val model = if offered.exists(_._1 == s("model")) then s("model") else offered.head._1
              val line = s("task").trim.linesIterator.next()
              val title = if line.length <= 80 then line
                else line.take(79).reverse.dropWhile(_ != ' ').reverse.trim + " …"
              sessions(id) = Session(id, s("project"), model, title, created = now, updated = now)
              kept(id) = Vector.empty
              heads(id) = (0L, Chain.start)
              say(id, s("task")).map(_.map(_ => summary(sessions(id)).toJson))
        yield out
      case "say" => say(s("session"), s("text")).map(_.map(_ => Json.Obj.empty))
      case "stop" => live.remove(s("session")) match
          case None => pure(Left("not running"))
          case Some(l) => l.acp.foreach(_.cancel()); l.agent.kill(); pure(Right(Json.Obj.empty))
      case "verdict" => verdict(s("call"), bool(body, "approve"), s("why"))
      case "policy" => judgeOf(s("text")) match
          case Left(e) => pure(Left(e))
          case Right(j) => setPolicy(s("text"), j).map(_ => Right(Json.Obj.empty))
      case "forget" => sessions.get(s("session")) match
          case None => pure(Left("no such session"))
          case Some(x) =>
            live.remove(x.id).foreach(_.agent.kill())
            sessions.remove(x.id); kept.remove(x.id); heads.remove(x.id)
            all(List(Files.remove[W](file(s"sessions/${x.id}.json")), Files.remove[W](file(s"sessions/${x.id}.jsonl")),
              Pages.tell[W]("forgot", Json.Str(x.id)))).map(_ => Right(Json.Obj.empty))
      case other => pure(Left(s"no act $other"))

  private def setPolicy(text: String, j: Judge): Unit ! W =
    policyNow = text; judge = Right(j); keepPolicy()
    Files.keep[W](file("policy.policy"), text).flatMap(_ => Pages.tell[W]("policy", Json.Str(text)))

  private def verdict(call: String, approve: Boolean, why: String): Either[String, Json] ! W =
    waits.remove(call) match
      case None => pure(Left("nothing waits for that"))
      case Some(w) =>
        w.answer(if approve then Verdict.Approve(acting.get(call).fold(w.input)(_.arguments))
          else Verdict.Refuse(if why.nonEmpty then why else "refused on the phone"))
        changed(w.session).map(_ => Right(Json.Obj.empty))

  /** ALWAYS ALLOW: a rule made of a waiting call, added to the policy if, added, it allows this call — then the call
   * approved. An `ask` still wins over an `allow`: then nothing is added, and the answer says which rule asks */
  private def always(call: String): Either[String, Json] ! W =
    waits.get(call) match
      case None => pure(Left("nothing waits for that"))
      case Some(w) =>
        val text = Rules.added(policyNow, w.tool, w.input)
        judgeOf(text) match
          case Left(e) => pure(Left(s"the rule did not compile: $e"))
          case Right(j) =>
            val decided = Promise[(String, String)]()
            j.decide(call, w.tool, w.input, sessions.get(w.session).fold("")(_.project))((v, why) =>
              val _ = decided.trySuccess((v, why)))
            Async.fromFuture[(String, String), W](decided.future).flatMap { (v, why) =>
              if v != "allow" then pure(Left(s"a rule still ${if v == "deny" then "denies" else "asks for"} it" +
                (if why.nonEmpty then s": $why" else "") + " — change the policy to allow it"))
              else setPolicy(text, j).flatMap(_ => verdict(call, true, "always allowed"))
                .map(_.map(_ => Json.obj("policy" -> Json.Str(text))))
            }

  /** CHANGES: the project's `git status` and `git diff` (staged and not), as git says them */
  private def diff(id: String): Either[String, Json] ! W =
    sessions.get(id) match
      case None => pure(Left("no such session"))
      case Some(x) =>
        Processes.run[W](List("git", "status", "--short"), x.project).flatMap { (c1, status) =>
          if c1 != 0 then pure(Left(s"git status: ${status.take(300)}"))
          else Processes.run[W](List("git", "diff", "HEAD", "--no-color", "--no-ext-diff"), x.project)
            .flatMap { (c2, d) =>
              // a repository with no commit yet has no HEAD: the working tree's diff then
              Processes.run[W](List("git", "diff", "--no-color", "--no-ext-diff"), x.project).map((_, unborn) =>
                Right(Json.obj("status" -> Json.Str(status), "diff" -> Json.Str(if c2 == 0 then d else unborn))))
            }
        }

  /** a person's words to a session: to its agent, started (or started again, resumed) if it is not running */
  private def say(id: String, text: String): Either[String, Unit] ! W =
    if text.trim.isEmpty then pure(Left("say something"))
    else sessions.get(id) match
      case None => pure(Left("no such session"))
      case Some(x) =>
        for
          _ <- loaded(id)
          _ <- if live.contains(id) then unit else run(x)
          _ <- record(id, Step.You(text))
          now <- Clock.now[W]
          _ = turns(id) = now
          _ = live.get(id).foreach(l => l.acp match
            case Some(a) => a.prompt(text)
            case None => l.agent.send(Json.print(Json.Obj(Vector("type" -> Json.Str("user"),
                "message" -> Json.obj("role" -> Json.Str("user"), "content" -> Json.Str(text)))))))
          _ = sessions(id) = sessions(id).copy(busy = true, updated = now)
          _ <- changed(id)
        yield Right(())

  private def run(x: Session): Unit ! W = Agents.of(x.model) match
    case Some(kind) => runAcp(x, kind)
    case None => runClaude(x)

  /** an agent over ACP: started in the project, its files and commands through the gate, its updates the steps */
  private def runAcp(x: Session, kind: AgentKind): Unit ! W =
    // the agent's commands, run here: in rozum's jail where there is one (writes in the project only, no secrets)
    def command(cmd: String, dir: String): Agent ! W =
      val sh = List("/bin/sh", "-c", cmd + " 2>&1")
      Processes.spawn[W](if jail.rozum then List("rozum", "launch", "--no-model", "--") ++ sh else sh, dir)
    // okay-guard's ACP adapter: on this machine (its files, its commands), through this gate, its events the steps
    val host = new Host:
      def read(path: String)(done: Option[String] => Unit): Unit = go(Files.read[W](path))(done)
      def write(path: String, text: String)(done: () => Unit): Unit = go(Files.save[W](path, text))(_ => done())
      def run(cmd: String, dir: String)(started: Line => Unit): Unit = go(command(cmd, dir))(started)
    for
      agent <- Processes.spawn[W](kind.command, x.project)
      key <- hex(16)
    yield
      val acp = AcpClient(host, agent, kind, x.project, x.agent,
        (call, tool, input) => answer => gate(x.id, call, tool, input)((v, why) => answer(v == "allow", why)),
        {
          case Event.Began(s, a) => fire(stepped(x.id, Step.Began(s, a)))
          case Event.Said(t) => fire(stepped(x.id, Step.Said(t)))
          case Event.Tool(i, n, in) => fire(stepped(x.id, Step.Tool(i, n, in)))
          case Event.Result(i, t, e) => fire(stepped(x.id, Step.Result(i, t, e)))
          case Event.Ended(ok, r) => fire(Clock.now[W].flatMap(now =>
              stepped(x.id, Step.Ended(ok, r, turns.get(x.id).fold(0L)(now - _), 0, 0))))
          case Event.Stopped(why) => fire(stepped(x.id, Step.Stopped(why)))
        })
      live(x.id) = Live(agent, key, Some(acp))
      sessions(x.id) = x.copy(running = true)
      ended(x.id, agent)
      acp.start()

  /** a step of a session's agent: kept, told; its start names the agent's session (to go on with later); a result
   * closes its call's record in the journal */
  private def stepped(id: String, st: Step): Unit ! W =
    st match
      case Step.Result(call, text, error) => acting.remove(call).foreach(a =>
          judging.done(a, if error then Left(text.take(400)) else Right(Json.Str(text)), None))
      case _ => ()
    val before: Unit ! W = st match
      case Step.Began(a, _) if a.nonEmpty && sessions.get(id).exists(_.agent != a) =>
        sessions(id) = sessions(id).copy(agent = a); save(id)
      case Step.Ended(_, _, _, _, _) => sessions.get(id).foreach(x => sessions(id) = x.copy(busy = false))
        changed(id)
      case _ => unit
    before.flatMap(_ => if sessions.contains(id) then record(id, st) else unit)

  private def ended(id: String, agent: Agent): Unit =
    agent.ended { why =>
      if live.get(id).exists(_.agent eq agent) then live.remove(id)
      waits.filter(_._2.session == id).keys.toList.foreach(k =>
        waits.remove(k).foreach(_.answer(Verdict.Refuse("the agent ended"))))
      if sessions.contains(id) then
        sessions(id) = sessions(id).copy(running = false, busy = false)
        fire(record(id, Step.Stopped(why)).flatMap(_ => changed(id)))
    }

  private def runClaude(x: Session): Unit ! W =
    hex(16).flatMap { key =>
      // every tool call through the hook; the daemon gone, the call is not done (exit 2: refused, the agent told)
      val hook = s"curl -sf --max-time ${askFor / 1000 + 60} -X POST -H content-type:application/json " +
        s"--data-binary @- http://127.0.0.1:${port()}/hook/${x.id}?k=$key || " +
        "{ echo 'okay-remote is not reachable: the call is not done' >&2; exit 2; }"
      val settings = Json.print(Json.obj("hooks" -> Json.obj("PreToolUse" -> Json.arr(Json.obj(
        "matcher" -> Json.Str("*"), "hooks" -> Json.arr(Json.obj("type" -> Json.Str("command"),
          "command" -> Json.Str(hook), "timeout" -> Json.num(askFor / 1000 + 60))))))))
      val (command, env) = Models.command(x.model, settings, x.agent, jail)
      Processes.spawn[W](command, x.project, env).map { agent =>
        live(x.id) = Live(agent, key)
        sessions(x.id) = x.copy(running = true)
        agent.lines(l => Step.ofAgent(l).foreach(st => st match
          case Step.Ended(ok, t, _, cost, n) => fire(Clock.now[W].flatMap(now =>
              stepped(x.id, Step.Ended(ok, t, turns.get(x.id).fold(0L)(now - _), cost, n))))
          case other => fire(stepped(x.id, other))))
        ended(x.id, agent)
      }
    }

  // ---- THE HOOK: a tool call the agent is about to make, decided

  /** Claude Code's PreToolUse hook's input: the answer is the hook's output (JSON), or a status and why */
  def hook(id: String, key: String, body: Json)(answer: Either[(Int, String), Json] => Unit): Unit =
    if !live.get(id).exists(l => Secret.same(l.key, key)) then answer(Left((403, "not this session's")))
    else go(hex(8)) { fresh =>
      val call = str(body, "tool_use_id").getOrElse(fresh)
      val tool = str(body, "tool_name").getOrElse("?")
      val input = get(body, "tool_input").getOrElse(Json.Obj.empty)
      def out(verdict: String, why: String) = answer(Right(Json.obj("hookSpecificOutput" -> Json.Obj(Vector(
        "hookEventName" -> Json.Str("PreToolUse"), "permissionDecision" -> Json.Str(verdict),
        "permissionDecisionReason" -> Json.Str(if why.nonEmpty then why else s"okay-remote: $verdict"))))))
      gate(id, call, tool, input)(out)
    }

  /** THE GATE (okay-guard's `Judging`): a call of the agent made an okay-guard `Action` — its tool the operation, the
   * agent's kind the service, its input and `cwd` the arguments — and decided by okay-guard's own program: the
   * policy,
   * a person asked if it asks (the phone; refused when the time is up), each step a journal entry. The answer: `allow`,
   * or `deny` and why; the action's record closed when its result comes (`stepped`) */
  def gate(id: String, call: String, tool: String, input: Json)(answer: (String, String) => Unit): Unit =
    val project = sessions.get(id).fold("")(_.project)
    val dir = if project.endsWith("/") then project else project + "/"
    val args = input match
      case o: Json.Obj => Json.Obj(o.fields.filterNot(_._1 == "cwd") :+ ("cwd" -> Json.Str(dir)))
      case _ => Json.obj("cwd" -> Json.Str(dir))
    val service = sessions.get(id).fold("")(x => Agents.of(x.model).fold("claude")(_.id))
    val action = Action(Json.Str(call), tool, args, service, "agent",
      Json.obj("session" -> Json.Str(id), "project" -> Json.Str(project)))
    acting(call) = action
    callers(call) = id
    judging(action) {
      case Outcome.Go(_) => answer("allow", "")
      case Outcome.Stop(why) => acting.remove(call); answer("deny", why)
      case Outcome.Pending(_, why) => acting.remove(call); answer("deny", why)
    }

  /** the actions that went, by call: closed in the journal when their result comes */
  private val acting = mutable.Map.empty[String, Action]
  /** whose session each call is */
  private val callers = mutable.Map.empty[String, String]

  /** what okay-guard's program asks of this place: the policy, the person (the phone), the clock, the journal */
  private val place: Gate = new Gate:
    def rules(call: okay.guard.Call): List[okay.guard.Rule] = Nil
    def now: Long = ports.time()
    override def decide(call: okay.guard.Call, answer: Judged => Unit): Unit = judge match
      case Right(j) => j.guard.decide(call, answer)
      case Left(e) => answer(Judged(Decision.Block(-1, s"the policy does not compile: $e")))
    override def approved(call: okay.guard.Call): Unit = judge.foreach(_.guard.approved(call))
    def ask(call: okay.guard.Call, reason: String, answer: Verdict => Unit): Unit =
      val key = call.id match { case Json.Str(s) => s; case o => Json.print(o) }
      val id = callers.getOrElse(key, "")
      val input = call.arguments match
        case o: Json.Obj => Json.Obj(o.fields.filterNot(_._1 == "cwd"))
        case other => other
      val w = Wait(id, key, call.operation, input, first(reason), answer)
      waits(key) = w
      fire(Pages.tell[W]("waiting", waitJson(w)).flatMap(_ => changed(id)))
      // no answer in time: refused
      fire(Clock.sleep[W](askFor).flatMap(_ => waits.remove(key) match
        case Some(w) => w.answer(Verdict.TimedOut); changed(id)
        case None => unit))
    def recorded(e: Entry): Unit =
      ledger(e)
      val key = e.id.collect { case Json.Str(s) => s }.getOrElse("")
      callers.get(key).foreach { id =>
        (e.kind, e.method) match
          case (Entry.Kind.Decided, v @ ("pass" | "block" | "ask")) =>
            val word = v match { case "pass" => "allow"; case "block" => "deny"; case _ => "ask" }
            fire(record(id, Step.Decided(key, word, if word == "allow" then "" else first(e.note))))
          case (Entry.Kind.Verdict, v) => fire(record(id, Step.Decided(key, v, first(e.note))))
          case _ => ()
      }
  private val judging = new Judging(place)(okay.effects.machine.Machine)
  /** a rule's word, without the rules that did not apply */
  private def first(why: String): String =
    why.split("; ").headOption.getOrElse(why).replaceFirst("^rule \\d+: ", "")

  // ---- THE JOURNAL (okay-guard's): one record an action, with its decision's proof and the policy it was made by
  private def policyHash: String = "sha256:" + okay.guard.Sha256.hex(policyNow)
  private def keepPolicy(): Unit = audit.policy(policyHash, policyNow)
  private val ledger = new Ledger(call => okay.guard.Context(
    call.context match { case o: Json.Obj => o.get("session").collect { case Json.Str(s) => s }.getOrElse("");
      case _ => "" }, call.service, None, Json.obj("hash" -> Json.Str(policyHash))), audit.record)

  /** each session's record checked: its chain unbroken, or where it breaks */
  def verify: List[(String, Either[String, Int])] ! W =
    each(sessions.keys.toList.sorted)(id => Files.read[W](file(s"sessions/$id.jsonl")).map(text =>
      id -> Chain.verify(text.toList.flatMap(_.linesIterator).flatMap(l => Descent.parse(l).toOption))))

  // ---- KEEPING AND TELLING

  private def waitJson(w: Wait): Json = Json.Obj(Vector("session" -> Json.Str(w.session), "call" -> Json.Str(w.id),
    "tool" -> Json.Str(w.tool), "input" -> w.input, "why" -> Json.Str(w.why)))

  private def summary(x: Session): Session = x.copy(running = live.contains(x.id),
    waiting = waits.values.count(_.session == x.id))

  /** a session's steps and the head of its chain, read from its file if they are not in memory yet */
  private def loaded(id: String): Unit ! W =
    if kept.contains(id) && heads.contains(id) then unit
    else Files.read[W](file(s"sessions/$id.jsonl")).map { text =>
      val records = text.toVector.flatMap(_.linesIterator).flatMap(l => Descent.parse(l).toOption)
      if !kept.contains(id) then kept(id) = records.flatMap(Step.fromJson)
      if !heads.contains(id) then heads(id) = records.lastOption.fold((0L, Chain.start))(j =>
        (Chain.seqOf(j), Chain.hashOf(j)))
    }

  private def record(id: String, st: Step): Unit ! W =
    loaded(id).flatMap { _ =>
      kept(id) = kept(id) :+ st
      val (seq, prev) = heads(id)
      val linked = Chain.link(seq + 1, prev, Step.toJson(st))
      heads(id) = (seq + 1, Chain.hashOf(linked))
      Files.append[W](file(s"sessions/$id.jsonl"), Json.print(linked) + "\n")
        .flatMap(_ => Pages.tell[W]("step", Json.obj("session" -> Json.Str(id), "step" -> Step.toJson(st))))
    }

  private def save(id: String): Unit ! W =
    sessions.get(id).fold(unit)(x => Files.keep[W](file(s"sessions/$id.json"), Json.print(x.toJson)))

  private def changed(id: String): Unit ! W =
    save(id).flatMap(_ => sessions.get(id).fold(unit)(x => Pages.tell[W]("session", summary(x).toJson)))

/** comparing secrets in a time that does not tell how much of them matched */
object Secret:
  def same(a: String, b: String): Boolean =
    a.length == b.length && a.zip(b).foldLeft(0)((acc, p) => acc | (p._1 ^ p._2)) == 0
