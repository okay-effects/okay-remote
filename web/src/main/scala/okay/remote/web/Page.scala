package okay.remote.web

import okay.business.scala3.*
import okay.effects.json.Json
import okay.remote.{J, Session, Step}
import okay.scala3.std.Lifted
import scala.concurrent.Future
import scala.scalajs.js

/**
 * THE PAGE AS AN OKAY-UI PROGRAM: its state a fold of the daemon's events; each state a `Screen` — the sessions, a
 * session as a conversation (your words, the agent's, its tool calls as cards with the policy's mark, a call waiting
 * for you with Approve and Refuse), a new task, the policy. A button pressed is a message; a message the next state
 * and the acts it asks of the daemon (`Live`).
 */
object Page:
  type Of[X] = (Lifted[Future] + Ui + Live)[X]
  private def live[X](asked: Live[X]): X ! Of = perform[Live](asked)

  final case class Info(projects: List[String] = Nil, sessions: Vector[Session] = Vector.empty,
    models: List[(String, String)] = Nil, through: Map[String, String] = Map.empty, policy: String = "",
    policyError: String = "",
    addresses: List[String] = Nil, pair: String = "", rozum: Boolean = false, claudeJailed: Boolean = false)

  final case class State(info: Info = Info(), place: String = "sessions", open: Option[String] = None,
    steps: Map[String, Vector[Step]] = Map.empty, waiting: Vector[Json] = Vector.empty, live: Option[Boolean] = None,
    problem: Option[String] = None, unfolded: Set[String] = Set.empty, address: String = "", saved: Boolean = false,
    unpaired: Boolean = false, changes: Option[(String, String)] = None, note: Option[String] = None):
    def session: Option[Session] = open.flatMap(id => info.sessions.find(_.id == id))
    def waitsFor(call: String): Option[Json] = waiting.find(w => J.str(w, "call").contains(call))

  enum Msg:
    case Came(events: List[(String, Json)])
    case Go(place: String)
    case Open(id: String)
    case Start(project: String, model: String, task: String)
    case Say(text: String)
    case Stop
    case Forget
    case Verdict(call: String, approve: Boolean)
    case PolicySave(text: String)
    case Fold(key: String)
    case Tick
    /** a rule made of the call, so that calls like it go at once */
    case Always(call: String)
    case Changes
    case Unpair

  enum Ask:
    case Act(body: Json, after: (State, Either[String, Json]) => State)
    /** the pairing given up here */
    case Forget

  // ---- THE PROGRAM

  def main: Unit ! Of =
    for
      events <- live(Live.Listen())
      address <- live(Live.Address())
      _ <- loop(State(address = address), events)
    yield ()

  def loop(s: State, events: Events[List[(String, Json)]]): Unit ! Of =
    show[Msg, Of](render(s).until(events.map(Msg.Came(_)))).flatMap { m =>
      val (next, asks) = step(s, m)
      asks.foldLeft(pure[State, Of](next))((p, ask) => p.flatMap(st => ask match
        case Ask.Act(body, after) => live(Live.Act(body)).map(after(st, _))
        case Ask.Forget => live(Live.Unpair()).map(_ => st)))
        .flatMap(loop(_, events))
    }

  private def act(kind: String, fields: (String, Json)*)(after: (State, Either[String, Json]) => State): Ask =
    Ask.Act(Json.Obj(("type" -> Json.Str(kind)) +: fields.toVector), after)
  private def failed(s: State, r: Either[String, Json]): State = r.fold(e => s.copy(problem = Some(e)), _ => s)

  def step(s: State, m: Msg): (State, List[Ask]) = m match
    case Msg.Came(events) => (events.foldLeft(s)(came), Nil)
    case Msg.Go(p) => (s.copy(place = p, problem = None, saved = false, note = None), Nil)
    case Msg.Always(call) => (s.copy(problem = None), List(act("always", "call" -> Json.Str(call))((st, r) => r match
        case Left(e) => st.copy(problem = Some(s"Not always allowed: $e"))
        case Right(_) => st.copy(waiting = st.waiting.filterNot(w => J.str(w, "call").contains(call)),
            note = Some("Allowed, and a rule added to the policy: calls like it now go at once.")))))
    case Msg.Changes => s.open.fold((s, Nil))(id => (s.copy(place = "changes", problem = None),
        List(act("diff", "session" -> Json.Str(id))((st, r) => r match
          case Left(e) => st.copy(problem = Some(e))
          case Right(j) =>
            st.copy(changes = Some((J.str(j, "status").getOrElse(""), J.str(j, "diff").getOrElse(""))))))))
    case Msg.Unpair => (s.copy(unpaired = true, live = Some(false)), List(Ask.Forget))
    case Msg.Tick => (s, Nil)
    case Msg.Fold(k) => (s.copy(unfolded = if s.unfolded(k) then s.unfolded - k else s.unfolded + k), Nil)
    case Msg.Open(id) => (s.copy(place = "session", open = Some(id), problem = None, note = None),
        List(act("open", "session" -> Json.Str(id))((st, r) => r match
          case Left(e) => st.copy(problem = Some(e))
          case Right(j) => st.copy(steps = st.steps + (id -> J.arr(j, "steps").flatMap(Step.fromJson)),
              waiting = st.waiting.filterNot(w => J.str(w, "session").contains(id)) ++ J.arr(j, "waiting")))))
    case Msg.Start(project, model, task) => (s.copy(problem = None), List(act("new", "project" -> Json.Str(project),
        "model" -> Json.Str(model), "task" -> Json.Str(task))((st, r) => r match
          case Left(e) => st.copy(problem = Some(e))
          case Right(j) => Session.fromJson(j).fold(st)(x => upsert(st, x).copy(place = "session", open = Some(x.id),
              steps = st.steps.updatedWith(x.id)(_.orElse(Some(Vector.empty))))))))
    case Msg.Say(text) => s.open.fold((s, Nil))(id =>
        (s.copy(problem = None), List(act("say", "session" -> Json.Str(id), "text" -> Json.Str(text))(failed))))
    case Msg.Stop => s.open.fold((s, Nil))(id => (s, List(act("stop", "session" -> Json.Str(id))(failed))))
    case Msg.Forget => s.open.fold((s, Nil))(id =>
        (s.copy(place = "sessions", open = None), List(act("forget", "session" -> Json.Str(id))(failed))))
    case Msg.Verdict(call, approve) =>
      (s.copy(waiting = s.waiting.filterNot(w => J.str(w, "call").contains(call))),
        List(act("verdict", "call" -> Json.Str(call), "approve" -> Json.Bool(approve))(failed)))
    case Msg.PolicySave(text) => (s.copy(problem = None, saved = false),
        List(act("policy", "text" -> Json.Str(text))((st, r) => r match
          case Left(e) => st.copy(problem = Some(s"Not saved: $e"))
          case Right(_) => st.copy(info = st.info.copy(policy = text, policyError = ""), saved = true))))

  private def upsert(s: State, x: Session): State =
    val others = s.info.sessions.filterNot(_.id == x.id)
    s.copy(info = s.info.copy(sessions = (x +: others).sortBy(-_.updated)))

  /** an event of the daemon, into the state */
  def came(s: State, e: (String, Json)): State = e match
    case ("live", Json.Bool(b)) => s.copy(live = Some(b))
    case ("unpaired", _) => s.copy(unpaired = true)
    case ("hello", j) => s.copy(live = Some(true), info = Info(
        J.arr(j, "projects").toList.collect { case Json.Str(p) => p },
        J.arr(j, "sessions").flatMap(Session.fromJson),
        J.arr(j, "models").toList.flatMap(m => for i <- J.str(m, "id"); l <- J.str(m, "label") yield i -> l),
        J.arr(j, "models").toList.flatMap(m => for i <- J.str(m, "id"); t <- J.str(m, "through") yield i -> t).toMap,
        J.str(j, "policy").getOrElse(""), J.str(j, "policyError").getOrElse(""),
        J.arr(j, "addresses").toList.collect { case Json.Str(a) => a }, J.str(j, "pair").getOrElse(""),
        J.get(j, "jail").exists(J.bool(_, "rozum")), J.get(j, "jail").exists(J.bool(_, "claude"))))
    case ("session", j) => Session.fromJson(j).fold(s)(upsert(s, _))
    case ("step", j) =>
      (J.str(j, "session"), J.get(j, "step").flatMap(Step.fromJson)) match
        case (Some(id), Some(st)) =>
          val done = st match
            case Step.Decided(call, v, _) if v != "ask" && v != "allow" && v != "deny" =>
              s.waiting.filterNot(w => J.str(w, "call").contains(call))
            case _ => s.waiting
          s.copy(waiting = done, steps = if s.steps.contains(id) then s.steps.updated(id, s.steps(id) :+ st)
            else s.steps)
        case _ => s
    case ("waiting", j) =>
      if s.waiting.exists(w => J.str(w, "call") == J.str(j, "call")) then s else s.copy(waiting = s.waiting :+ j)
    case ("policy", Json.Str(p)) => s.copy(info = s.info.copy(policy = p, policyError = ""))
    case ("forgot", Json.Str(id)) => s.copy(info = s.info.copy(sessions = s.info.sessions.filterNot(_.id == id)),
        steps = s.steps - id, open = s.open.filterNot(_ == id), place = if s.open.contains(id) then "sessions"
        else s.place)
    case _ => s

  // ---- THE SCREENS

  val places: List[(String, String)] =
    List("sessions" -> "Sessions", "new" -> "New", "policy" -> "Policy", "phone" -> "Phone")

  def render(s: State): Screen[Msg] =
    if s.unpaired then unpaired else rendered(s)

  def unpaired: Screen[Msg] = Screen[Msg]("okay-remote", List(
    Part.block("top")(Part.block("brand")(Part.text("okay-remote")), Part.block("live-off")(Part.text("not paired"))),
    Part.block("pairing")(Part.text("Pair this phone with your computer"),
      Part.text("On your computer: java -jar okay-remote.jar --relay, open the address it prints, then Phone. " +
        "Scan its QR code with this phone's camera. The code holds a secret that never leaves your devices: the " +
        "relay " +
        "passes your sessions sealed end to end, and cannot read them."))), Nil)

  def rendered(s: State): Screen[Msg] =
    val waits = s.waiting.size
    val nav = places.map((p, label) => Button(label + (if p == "sessions" && waits > 0 then s" · $waits" else ""),
      if s.place == p || (p == "sessions" && s.place == "session") then Style.Primary
      else if p == "sessions" && waits > 0 then Style.Danger else Style.Plain)(Msg.Go(p): Msg).at("places"))
    val (state, word) = s.live match
      case Some(true) => ("on", "connected")
      case Some(false) => ("off", "the machine is not reached")
      case None => ("wait", "connecting")
    val head = List(Part.block(s"place-${s.place}")(),
      Part.block("top")(Part.block("brand")(Part.text("okay-remote")), Part.block(s"live-$state")(Part.text(word))),
      Part.block("places")(Part.slot("places")))
    val problem = s.problem.toList.map(Part.problem) ++ s.note.toList.map(Part.info)
    val (parts, buttons) = s.place match
      case "session" => s.session.fold(sessions(s))(session(s, _))
      case "new" => fresh(s)
      case "policy" => policy(s)
      case "phone" => phone(s)
      case "changes" => s.session.fold(sessions(s))(changes(s, _))
      case _ => sessions(s)
    Screen[Msg]("okay-remote", head ++ problem ++ parts, nav ++ buttons)

  private def short(project: String): String = project.split('/').lastOption.getOrElse(project)
  private def modelLabel(s: State, m: String): String = s.info.models.find(_._1 == m).map(_._2).getOrElse(m)
  private def ago(ms: Long): String =
    val secs = ((js.Date.now() - ms) / 1000).toLong
    if secs < 60 then "now" else if secs < 3600 then s"${secs / 60} min ago"
    else if secs < 86400 then s"${secs / 3600} h ago" else s"${secs / 86400} d ago"

  def sessions(s: State): (List[Part], List[Button[Msg]]) =
    if s.info.sessions.isEmpty then
      (List(Part.block("empty")(Part.text("No sessions yet. Start one: a project, a model, a task.")),
        Part.slot("first")), List(Button("A new task", Style.Primary)(Msg.Go("new"): Msg).at("first")))
    else
      val rows = s.info.sessions.toList.map { x =>
        val waits = s.waiting.count(w => J.str(w, "session").contains(x.id))
        val state = if waits > 0 then ("wait", s"waiting for you · $waits")
          else if x.busy then ("busy", "working") else if x.running then ("idle", "ready")
          else ("off", "stopped")
        Part.block(s"row-${state._1}")(Part.slot(s"open-${x.id}"),
          Part.block("row-small")(Part.text(s"${short(x.project)} · ${modelLabel(s, x.model)} · ${state._2} · " +
            ago(x.updated))))
      }
      (rows, s.info.sessions.toList.map(x => Button(x.title)(Msg.Open(x.id): Msg).at(s"open-${x.id}")))

  /** a tool call in a line: what matters of its input */
  private def gist(tool: String, input: Json): String =
    val keys = List("command", "file_path", "notebook_path", "pattern", "path", "url", "query", "description",
      "prompt")
    keys.flatMap(J.str(input, _)).headOption.map(_.linesIterator.next()).getOrElse(Json.print(input)).take(160)

  def session(s: State, x: Session): (List[Part], List[Button[Msg]]) =
    val all = s.steps.getOrElse(x.id, Vector.empty)
    val decided = all.collect { case Step.Decided(id, v, why) => id -> (v, why) }.toMap
    // an agent may tell a call twice (first empty, then with its input): one card, where it came first
    val inputs = all.collect { case Step.Tool(id, _, in) => id -> in }.toMap
    val firstAt = all.zipWithIndex.collect { case (Step.Tool(id, _, _), i) => id -> i }
      .groupMapReduce(_._1)(_._2)(_ min _)
    val results = all.collect { case Step.Result(id, t, e) => id -> (t, e) }.toMap
    val buttons = List.newBuilder[Button[Msg]]
    def folded(key: String, text: String, limit: Int): List[Part] =
      if text.length <= limit then List(Part.text(text))
      else
        buttons += Button(if s.unfolded(key) then "Fold" else "Show all")(Msg.Fold(key): Msg).at(s"fold-$key")
        List(Part.text(if s.unfolded(key) then text else text.take(limit) + " …"),
          Part.block("unfold")(Part.slot(s"fold-$key")))
    val items = all.zipWithIndex.toList.flatMap {
      case (Step.You(t), _) => List(Part.block("you")(Part.text(t)))
      case (Step.Began(_, model), _) => List(Part.block("small")(Part.text(s"Claude Code · $model")))
      case (Step.Said(t), i) => List(Part.block("agent")(folded(s"said-$i", t, 1200)*))
      case (Step.Tool(id, _, _), i) if firstAt.get(id).exists(_ != i) => Nil
      case (Step.Tool(id, name, _), _) =>
        val input = inputs.getOrElse(id, Json.Obj.empty)
        val waits = s.waitsFor(id).nonEmpty
        val (mark, word) = if waits then ("wait", "waiting for you") else decided.get(id) match
          case Some(("deny", why)) => ("block", s"denied${if why.nonEmpty then s": $why" else ""}")
          case Some(("refused", _)) => ("block", "refused by you")
          case Some(("timed out", _)) => ("block", "no answer in time")
          case Some(("approved", _)) => ("pass", "approved by you")
          case Some(("ask", why)) => ("wait", s"asked${if why.nonEmpty then s": $why" else ""}")
          case Some(("allow", _)) => ("pass", "allowed")
          case _ => ("none", "")
        val result = results.get(id).toList.flatMap((t, e) =>
          List(Part.block(if e then "result-error" else "result")(folded(s"r-$id", t, 300)*)))
        if waits then
          buttons += Button("Approve", Style.Primary)(Msg.Verdict(id, true): Msg).at(s"w-$id")
          buttons += Button("Always allow")(Msg.Always(id): Msg).at(s"w-$id")
          buttons += Button("Refuse", Style.Danger)(Msg.Verdict(id, false): Msg).at(s"w-$id")
        List(Part.block(s"call-$mark")((List(Part.block("call-head")(Part.text(name)),
          Part.block("call-gist")(Part.text(gist(name, input)))) ++
          (if word.nonEmpty then List(Part.block("call-word")(Part.text(word))) else Nil) ++
          (if waits then List(Part.slot(s"w-$id")) else Nil) ++ result)*))
      case (Step.Result(_, _, _), _) => Nil
      case (Step.Decided(_, _, _), _) => Nil
      case (Step.Ended(ok, t, ms, cost, turns), i) =>
        val line = s"${if ok then "Done" else "Not done"} · ${ms / 1000} s" +
          (if turns > 0 then s" · $turns turns" else "") +
          // a local model costs nothing: Claude Code's sum is by Claude's prices
          (if x.model.startsWith("local:") || cost <= 0 then "" else if cost >= 0.01 then f" · $$$cost%.2f"
          else f" · $$$cost%.3f")
        List(Part.block(if ok then "end" else "end-failed")((Part.block("small")(Part.text(line)) ::
          (if !ok && t.nonEmpty then folded(s"end-$i", t, 300) else Nil))*))
      case (Step.Stopped(why), _) => List(Part.block("small")(Part.text(s"The agent stopped ($why).")))
    }
    val head = Part.block("session-head")(Part.text(x.title),
      Part.block("small")(Part.text(s"${short(x.project)} · ${modelLabel(s, x.model)}" +
        (if x.busy then " · working" else if x.running then " · ready" else " · stopped"))))
    val next = Form.one(Field.longText(if x.running then "Next" else "Next (the agent starts again)"))
    val acts = List(Button.submit("Send", next)(Msg.Say(_): Msg).at("composer"),
      Button("Changes")(Msg.Changes: Msg).at("composer")) ++
      (if x.running then List(Button("Stop", Style.Danger)(Msg.Stop: Msg).at("composer"))
      else List(Button("Forget", Style.Danger)(Msg.Forget: Msg).at("composer")))
    (head :: items ++ List(Part.block("composer")(next, Part.slot("composer"))), buttons.result() ++ acts)

  /** CHANGES: what the agent changed in the project, as git says it */
  def changes(s: State, x: Session): (List[Part], List[Button[Msg]]) =
    val back = Button("Back to the session", Style.Primary)(Msg.Open(x.id): Msg).at("changes")
    val again = Button("Read again")(Msg.Changes: Msg).at("changes")
    val body = s.changes match
      case None => List(Part.block("small")(Part.text("Asking git…")))
      case Some((status, diff)) =>
        List(Part.block("small")(Part.text(s"${short(x.project)} · git status")),
          Part.block("result")(Part.text(if status.trim.isEmpty then "nothing changed" else status))) ++
          (if diff.trim.isEmpty then Nil else List(Part.block("diff")(Part.text(diff))))
    (Part.block("session-head")(Part.text(x.title)) :: Part.slot("changes") :: body, List(back, again))

  def fresh(s: State): (List[Part], List[Button[Msg]]) =
    if s.info.projects.isEmpty then (List(Part.info("No projects found: a directory with .git under the roots " +
      "(~/.okay-remote/roots, a line each; ~/work if there is none).")), Nil)
    else
      val form = Form((Field.choice("Project", s.info.projects.map(p => short(p) -> p)*),
        Field.choice("Agent", s.info.models.map((i, l) => l -> i)*), Field.longText("Task")))
      val agents = s.info.models.map((i, l) => s"$l: ${s.info.through.getOrElse(i, "")}").mkString("\n")
      (List(Part.block("new")(form), Part.block("small")(Part.text("Through the policy, by agent:\n" + agents))),
        List(Button.submit("Start", form)((p, m, t) => Msg.Start(p, m, t): Msg)))

  def policy(s: State): (List[Part], List[Button[Msg]]) =
    val form = Form.one(Field.longText("The policy").initially(s.info.policy))
    val notes = (if s.info.policyError.nonEmpty then List(Part.problem(s"It does not compile, so every call is " +
      s"refused: ${s.info.policyError}")) else Nil) ++ (if s.saved then List(Part.info("Saved: it decides the " +
      "next call.")) else Nil)
    val sandbox = "The sandbox: " + (if !s.info.rozum then "none — install rozum to run the agents jailed (writes " +
      "only in the project, secrets unreadable)" else "rozum — local models in it; Claude " +
      (if s.info.claudeJailed then "in it too" else "outside it (a token from `claude setup-token` in " +
        "~/.okay-remote/claude-token jails it too)"))
    (List(Part.block("small")(Part.text("Every tool call of every session is decided by this policy, in " +
      "okay-guard's language: allow, ask (your phone), or deny. cwd is the project's directory.")),
      Part.block("small")(Part.text(sandbox))) ++ notes ++ List(Part.block("policy")(form)),
      List(Button.submit("Save", form)(Msg.PolicySave(_): Msg)))

  def phone(s: State): (List[Part], List[Button[Msg]]) =
    val token = s.address.split("\\?t=").drop(1).headOption.getOrElse("")
    val port = s.address.split("://").drop(1).headOption.flatMap(_.split('/').headOption).flatMap(_.split(':')
      .drop(1).headOption).getOrElse("7788")
    val lan = s.info.addresses.map(a => s"http://$a:$port/?t=$token")
    val warning = Part.block("small")(Part.text("Whoever has this code runs agents on this machine: show it to " +
      "no one " +
      "else. To shut every paired phone out: okay-remote --pair-again."))
    if s.address.isEmpty then
      // through the relay: this phone is paired
      (List(Part.info("This phone is paired: your sessions reach it through the relay, sealed end to end."),
        Part.slot("unpair")), List(Button("Unpair this phone", Style.Danger)(Msg.Unpair: Msg).at("unpair")))
    else if s.info.pair.nonEmpty then
      (List(Part.block("small")(Part.text("Scan it with your phone: it works from anywhere, through the relay, " +
        "sealed end to end.")), Part.block("qr")(Part.qr(s.info.pair, "pair your phone"))) ++
        lan.map(u => Part.block("small")(Part.text(s"On the same network: $u"))) :+ warning, Nil)
    else if lan.nonEmpty then
      (lan.map(u => Part.block("qr")(Part.qr(u, "this page on your phone"), Part.block("address")(Part.text(u)))) :+
        warning, Nil)
    else (List(Part.info("To work from your phone, start okay-remote with --relay (from anywhere, through the " +
      "relay, sealed end to end) or --lan (on the same network); a QR code shows here then.")), Nil)
