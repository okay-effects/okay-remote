package okay.remote

import okay.effects.json.{Descent, Json}
import scala.collection.mutable

/** THE CODING AGENTS a session may run (specs/remote.md, "Any agent"): Claude Code by its own hook, the others by
 * ACP — each with the command that starts it, what must be installed, and what goes through the policy */
final case class AgentKind(id: String, label: String, command: List[String], needs: List[String], through: String)

object Agents:
  private val acp = "files and commands it runs, and what it asks"
  val all: List[AgentKind] = List(
    AgentKind("claude-acp", "Claude Code (ACP)", List("npx", "-y", "@zed-industries/claude-code-acp"),
      List("claude", "npx"), acp),
    AgentKind("codex", "Codex", List("npx", "-y", "@zed-industries/codex-acp"), List("codex", "npx"), acp),
    // the approval mode that asks of every edit and command — never the agent's own auto-approval (a model judging)
    AgentKind("gemini", "Gemini CLI", List("gemini", "--experimental-acp", "--approval-mode", "default"),
      List("gemini"), acp),
    AgentKind("qwen", "Qwen Code", List("qwen", "--acp", "--approval-mode", "default"), List("qwen"), acp),
    AgentKind("opencode", "opencode", List("opencode", "acp"), List("opencode"), acp),
    AgentKind("goose", "Goose", List("goose", "acp"), List("goose"), acp))
  def of(model: String): Option[AgentKind] = all.find(a => model == s"acp:${a.id}")

/** a gate: a call of the agent — its card's id, the tool by the policy's name, its input — decided: `allow` or
 * `deny` (asked of a person in between, if the policy asks), and why */
type Gate = (String, String, Json) => ((String, String) => Unit) => Unit

/**
 * A SESSION WITH AN AGENT OVER ACP (the Agent Client Protocol, JSON-RPC over the agent's stdin and stdout). This end
 * is the client, and says so: it reads and writes the files, it runs the terminals — so what the agent writes and
 * runs is done HERE, after the gate (the policy, the phone): `fs/write_text_file` is `Write`, `terminal/create` is
 * `Bash`, `fs/read_text_file` is `Read`. What the agent does itself it may ask first (`session/request_permission`):
 * the gate answers that too, by the tool's kind. The agent's updates become the session's steps.
 */
final class AcpSession(m: Machine, agent: Agent, kind: AgentKind, project: String, resume: String,
                       gate: Gate, emit: Step => Unit, runCommand: (String, String) => Agent):
  import J.*
  private var n = 0
  private val answers = mutable.Map.empty[String, Json => Unit]
  private var session: Option[String] = None
  private var canLoad = false
  private var queued = Vector.empty[String]
  private var loading = false
  private val said = new StringBuilder
  /** the agent's tool calls by id: its name and input (the cards a file or a command is matched to) */
  private val calls = mutable.LinkedHashMap.empty[String, (String, Json)]
  /** what a person or the policy already allowed through a permission: not asked again when it comes to be done */
  private val allowed = mutable.Map.empty[String, Long]
  private var started = 0L

  private def send(j: Json): Unit = agent.send(Json.print(j))
  private def request(method: String, params: Json)(onAnswer: Json => Unit): Unit =
    n += 1
    val id = Json.Str(s"r$n")
    answers(Json.print(id)) = onAnswer
    send(Json.Obj(Vector("jsonrpc" -> Json.Str("2.0"), "id" -> id, "method" -> Json.Str(method), "params" -> params)))
  private def reply(id: Json, result: Json): Unit =
    send(Json.Obj(Vector("jsonrpc" -> Json.Str("2.0"), "id" -> id, "result" -> result)))
  private def fail(id: Json, message: String): Unit =
    send(Json.Obj(Vector("jsonrpc" -> Json.Str("2.0"), "id" -> id,
      "error" -> Json.obj("code" -> Json.num(-32000), "message" -> Json.Str(message)))))

  def start(): Unit =
    agent.lines(line => Descent.parse(line).foreach(got))
    request("initialize", Json.obj("protocolVersion" -> Json.num(1), "clientCapabilities" -> Json.obj(
      "fs" -> Json.obj("readTextFile" -> Json.Bool(true), "writeTextFile" -> Json.Bool(true)),
      "terminal" -> Json.Bool(true)))) { r =>
      canLoad = get(r, "agentCapabilities").exists(bool(_, "loadSession"))
      if resume.nonEmpty && canLoad then
        loading = true
        request("session/load", Json.obj("sessionId" -> Json.Str(resume), "cwd" -> Json.Str(project),
          "mcpServers" -> Json.arr())) { r =>
          loading = false
          if get(r, "code").isEmpty then ready(resume) else fresh()
        }
      else fresh()
    }

  private def fresh(): Unit =
    request("session/new", Json.obj("cwd" -> Json.Str(project), "mcpServers" -> Json.arr())) { r =>
      str(r, "sessionId") match
        case Some(id) => ready(id)
        case None => emit(Step.Stopped(s"${kind.label} did not start a session: " +
            get(r, "message").map(x => Json.print(x)).getOrElse(Json.print(r))))
    }

  private def ready(id: String): Unit =
    session = Some(id)
    emit(Step.Began(id, kind.label))
    val q = queued; queued = Vector.empty
    q.foreach(prompt)

  /** a person's words: the next prompt (kept until the session is there) */
  def prompt(text: String): Unit = session match
    case None => queued :+= text
    case Some(id) =>
      started = m.now
      request("session/prompt", Json.obj("sessionId" -> Json.Str(id),
        "prompt" -> Json.arr(Json.obj("type" -> Json.Str("text"), "text" -> Json.Str(text))))) { r =>
        flush()
        val reason = str(r, "stopReason").getOrElse(get(r, "message").map(x => Json.print(x)).getOrElse("failed"))
        emit(Step.Ended(reason == "end_turn", if reason == "end_turn" then "" else reason, m.now - started, 0, 0))
      }

  def cancel(): Unit = session.foreach(id => send(Json.obj("jsonrpc" -> Json.Str("2.0"),
    "method" -> Json.Str("session/cancel"), "params" -> Json.obj("sessionId" -> Json.Str(id)))))

  private def flush(): Unit = if said.nonEmpty then
    val t = said.toString; said.clear()
    if t.trim.nonEmpty then emit(Step.Said(t.trim))

  // ---- WHAT THE AGENT SAYS AND ASKS

  private def got(j: Json): Unit =
    (get(j, "id"), str(j, "method")) match
      case (Some(id), None) => answers.remove(Json.print(id)).foreach(_(get(j, "result").orElse(get(j, "error"))
          .getOrElse(Json.Null)))
      case (None, Some("session/update")) => if !loading then get(j, "params").flatMap(get(_, "update"))
          .foreach(update)
      case (Some(id), Some(method)) => asked(id, method, get(j, "params").getOrElse(Json.Obj.empty))
      case _ => ()

  private def update(u: Json): Unit = str(u, "sessionUpdate").getOrElse("") match
    case "agent_message_chunk" => get(u, "content").flatMap(str(_, "text")).foreach(said.append)
    case "tool_call" =>
      flush()
      val id = str(u, "toolCallId").getOrElse(m.randomHex(6))
      val name = toolName(u)
      val input = get(u, "rawInput").filter(_ != Json.Obj.empty).getOrElse(Json.Obj.empty)
      val was = calls.get(id)
      // a call told again with its input (agents send it pending and empty first): its card filled in
      if was.isEmpty || (was.exists(_._2 == Json.Obj.empty) && input != Json.Obj.empty) then
        calls(id) = (name, input)
        emit(Step.Tool(id, name, input))
    case "tool_call_update" =>
      val id = str(u, "toolCallId").getOrElse("")
      str(u, "status") match
        case Some(s @ ("completed" | "failed")) => emit(Step.Result(id, output(u), s == "failed"))
        case _ => get(u, "rawInput").filter(_ != Json.Obj.empty).foreach { input =>
            if calls.get(id).exists(_._2 == Json.Obj.empty) then
              val name = calls(id)._1
              calls(id) = (name, input)
              emit(Step.Tool(id, name, input))
          }
    case "plan" =>
      flush()
      val entries = arr(u, "entries").map(e => (if str(e, "status").contains("completed") then "☑ " else "☐ ") +
        str(e, "content").getOrElse(""))
      if entries.nonEmpty then emit(Step.Said("Plan:\n" + entries.mkString("\n")))
    case _ => ()

  private def toolName(u: Json): String =
    get(u, "_meta").flatMap(get(_, "claudeCode")).flatMap(str(_, "toolName")).map(_.stripPrefix("mcp__acp__"))
      .orElse(str(u, "title")).getOrElse("tool")

  private def output(u: Json): String =
    val parts = arr(u, "content").flatMap(c => get(c, "content").flatMap(str(_, "text")).orElse(str(c, "text"))
      .orElse(str(c, "path").map(p => s"[$p]")))
    if parts.nonEmpty then parts.mkString("\n") else get(u, "rawOutput") match
      case Some(Json.Str(s)) => s
      // a list of parts, each with its text (Claude's adapter)
      case Some(Json.Arr(items)) if items.exists(i => str(i, "text").nonEmpty) => items.flatMap(str(_, "text"))
          .mkString("\n")
      case Some(o) => List("stdout", "output", "text").flatMap(str(o, _)).headOption
          .getOrElse(Json.print(o).take(4000))
      case None => ""

  /** the card a file or a command is matched to: the newest call whose input says it; or a card of its own */
  private def card(tool: String, key: String, value: String, input: Json): String =
    calls.toList.reverse.collectFirst { case (id, (_, in)) if str(in, key).contains(value) => id }.getOrElse {
      val id = s"$tool-${m.randomHex(4)}"
      calls(id) = (tool, input)
      emit(Step.Tool(id, tool, input))
      id
    }

  private def through(tool: String, key: String, value: String, input: Json)(go: => Unit, no: String => Unit): Unit =
    val id = card(tool, key, value, input)
    val k = s"$tool\u0000$value"
    if allowed.get(k).exists(t => m.now - t < 120000) then
      allowed.remove(k)
      go
    else gate(id, tool, input) { (v, why) => if v == "allow" then go else no(why) }

  private def asked(id: Json, method: String, p: Json): Unit = method match
    case "fs/read_text_file" =>
      val path = str(p, "path").getOrElse("")
      through("Read", "file_path", path, Json.obj("file_path" -> Json.Str(path)))(
        m.read(path) match
          case None => fail(id, s"no such file: $path")
          case Some(text) =>
            val from = long(p, "line").toInt.max(1) - 1
            val lines = text.split("\n", -1).drop(from)
            val limit = long(p, "limit").toInt
            reply(id, Json.obj("content" -> Json.Str((if limit > 0 then lines.take(limit) else lines).mkString("\n"))))
        , why => fail(id, s"okay-remote: not read — $why"))
    case "fs/write_text_file" =>
      val path = str(p, "path").getOrElse("")
      val content = str(p, "content").getOrElse("")
      through("Write", "file_path", path, Json.obj("file_path" -> Json.Str(path), "content" -> Json.Str(content)))(
        { m.save(path, content); reply(id, Json.Null) }, why => fail(id, s"okay-remote: not written — $why"))
    case "terminal/create" =>
      val command = (str(p, "command").getOrElse("") :: arr(p, "args").toList.collect { case Json.Str(a) =>
        "'" + a.replace("'", "'\\''") + "'" }).mkString(" ")
      val dir = str(p, "cwd").getOrElse(project)
      through("Bash", "command", command, Json.obj("command" -> Json.Str(command)))(
        { reply(id, Json.obj("terminalId" -> Json.Str(terminal(command, dir, long(p, "outputByteLimit"))))) },
        why => fail(id, s"okay-remote: not run — $why"))
    case "terminal/output" => terminals.get(str(p, "terminalId").getOrElse("")) match
        case None => fail(id, "no such terminal")
        case Some(t) => reply(id, Json.Obj(Vector("output" -> Json.Str(t.text), "truncated" -> Json.Bool(t.cut)) ++
            t.code.map(c => "exitStatus" -> Json.obj("exitCode" -> Json.num(c))).toVector))
    case "terminal/wait_for_exit" => terminals.get(str(p, "terminalId").getOrElse("")) match
        case None => fail(id, "no such terminal")
        case Some(t) => t.whenDone(c => reply(id, Json.obj("exitCode" -> Json.num(c))))
    case "terminal/kill" => terminals.get(str(p, "terminalId").getOrElse("")).foreach(_.proc.kill())
        reply(id, Json.Null)
    case "terminal/release" => terminals.remove(str(p, "terminalId").getOrElse("")).foreach(_.proc.kill())
        reply(id, Json.Null)
    case "session/request_permission" =>
      val call = get(p, "toolCall").getOrElse(Json.Obj.empty)
      val callId = str(call, "toolCallId").getOrElse(m.randomHex(6))
      val (tool, input) = asTool(call)
      val options = arr(p, "options")
      def pick(kinds: String*) = kinds.flatMap(k => options.find(o => str(o, "kind").contains(k))).headOption
        .flatMap(str(_, "optionId"))
      if !calls.contains(callId) then
        calls(callId) = (tool, input)
        emit(Step.Tool(callId, tool, input))
      gate(callId, tool, input) { (v, _) =>
        if v == "allow" then
          List("command", "file_path").flatMap(str(input, _)).foreach(x => allowed(s"$tool\u0000$x") = m.now)
          if tool == "Edit" then str(input, "file_path").foreach(x => allowed(s"Write\u0000$x") = m.now)
        val chosen = if v == "allow" then pick("allow_once", "allow_always") else pick("reject_once", "reject_always")
        reply(id, Json.obj("outcome" -> chosen.fold(Json.obj("outcome" -> Json.Str("cancelled")))(o =>
          Json.obj("outcome" -> Json.Str("selected"), "optionId" -> Json.Str(o)))))
      }
    case other => fail(id, s"okay-remote does not do $other")

  /** a call the agent asks permission for, by the policy's names: its kind, its input, the files it touches */
  private def asTool(call: Json): (String, Json) =
    val raw = get(call, "rawInput").getOrElse(Json.Obj.empty)
    val path = arr(call, "locations").headOption.flatMap(str(_, "path")).orElse(str(raw, "file_path"))
      .orElse(str(raw, "path"))
    def withPath(k: String) = raw match
      case o: Json.Obj => Json.Obj(o.fields.filterNot(_._1 == k) ++ path.map(k -> Json.Str(_)).toVector)
      case _ => Json.Obj(path.map(k -> Json.Str(_)).toVector)
    str(call, "kind").getOrElse("other") match
      case "edit" => ("Edit", withPath("file_path"))
      case "delete" => ("Delete", withPath("file_path"))
      case "move" => ("Move", withPath("file_path"))
      case "read" => ("Read", withPath("file_path"))
      case "execute" => ("Bash", raw match
          case o: Json.Obj if o.has("command") => o
          case _ => Json.obj("command" -> Json.Str(str(call, "title").getOrElse(""))))
      case "fetch" => ("WebFetch", raw)
      case "search" => ("Grep", raw)
      case _ => (get(call, "_meta").flatMap(get(_, "claudeCode")).flatMap(str(_, "toolName"))
          .orElse(str(call, "title")).getOrElse("tool"), raw)

  // ---- THE TERMINALS: the agent's commands, run here (in the jail where there is one)

  private final class Term(val proc: Agent, limit: Long):
    private val out = new StringBuilder
    var cut = false
    var code: Option[Int] = None
    private var waiting = List.empty[Int => Unit]
    def text: String = out.toString
    def add(line: String): Unit =
      out.append(line).append('\n')
      if limit > 0 && out.length > limit then { out.delete(0, out.length - limit.toInt); cut = true }
    def end(c: Int): Unit = { code = Some(c); waiting.foreach(_(c)); waiting = Nil }
    def whenDone(f: Int => Unit): Unit = code.fold(waiting ::= f)(f)
  private val terminals = mutable.Map.empty[String, Term]

  private def terminal(command: String, dir: String, limit: Long): String =
    val id = s"t${m.randomHex(4)}"
    val proc = runCommand(command, dir)
    val t = Term(proc, if limit > 0 then limit else 200000)
    terminals(id) = t
    proc.lines(t.add)
    proc.ended(why => t.end("\\((-?\\d+)\\)".r.findFirstMatchIn(why).map(_.group(1).toInt).getOrElse(1)))
    id
