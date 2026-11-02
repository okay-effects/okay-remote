package okay.remote.daemon

import okay.effects.json.{Descent, Json}
import okay.guard.local.{Stream, Web, WebRequest}
import okay.llm.AgUi
import okay.remote.J
import scala.collection.mutable

/** EVERY SESSION AN AG-UI AGENT: `POST /agui` (RunAgentInput) — the thread's session told the user's words (a new
 * session for a new thread: `forwardedProps.project`, `.model`), its steps streamed as AG-UI events until it ends;
 * a call waiting for the phone ends the run with an interrupt (an approval), the next run's `resume` its verdict.
 * Whatever agent the session runs: Claude Code, opencode, okay-agent. On the daemon's loop */
final class AgUiBridge(act: Json => (Either[String, Json] => Unit) => Unit):
  /** a run on: its stream, its thread, its id, its messages counted */
  private final class Run(val stream: Stream, val thread: String, val id: String):
    var n = 0
    /** a message streamed now: its id, how much of its text was sent */
    var typing: Option[(String, Int)] = None
    def emit(e: Json): Unit = stream.write(AgUi.sse(e))
    def message(): String = { n += 1; s"$id-m$n" }
  private val sessions = mutable.Map.empty[String, String]
  private val runs = mutable.Map.empty[String, Run]

  def serve(r: WebRequest, w: Web): Unit =
    Descent.parse(r.body) match
      case Left(_) =>
        w.reply(400, ("Content-Type" -> "application/json") :: AgUiBridge.cors, """{"error":"not JSON"}""")
      case Right(body) =>
        val in = AgUi.RunInput.parse(body)
        val run = Run(w.stream(List("Content-Type" -> "text/event-stream", "Cache-Control" -> "no-store") ++
          AgUiBridge.cors),
          in.threadId, in.runId)
        run.emit(AgUi.runStarted(in.threadId, in.runId))
        def fail(why: String) = { run.emit(AgUi.runError(why)); run.stream.close() }
        val props = J.get(body, "forwardedProps").getOrElse(Json.Obj.empty)
        sessions.get(in.threadId).orElse(Option.when(in.resume.nonEmpty)(in.threadId)) match
          case Some(session) if in.resume.nonEmpty =>
            runs(session) = run
            in.resume.foreach(a => act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str(a.interruptId),
              "approve" -> Json.Bool(a.resolved && a.payload("approved") == Json.Bool(true)),
              "why" -> a.payload.field("reason").getOrElse(Json.Str("")))) {
                case Left(why) => runs.remove(session); fail(why)
                case Right(_) => ()
              })
          case Some(session) => in.lastUserText match
              case None => fail("no user message")
              case Some(text) =>
                runs(session) = run
                act(Json.obj("type" -> Json.Str("say"), "session" -> Json.Str(session), "text" -> Json.Str(text))) {
                  case Left(why) => runs.remove(session); fail(why)
                  case Right(_) => ()
                }
          case None => (in.lastUserText, J.str(props, "project")) match
              case (None, _) => fail("no user message")
              case (_, None) => fail("a new thread: forwardedProps.project — the project's directory")
              case (Some(text), Some(project)) =>
                act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str(okay.remote.Paths.real(project)),
                  "model" -> Json.Str(J.str(props, "model").getOrElse("")), "task" -> Json.Str(text))) {
                  case Left(why) => fail(why)
                  case Right(s) => J.str(s, "id") match
                      case None => fail("no session")
                      case Some(id) =>
                        sessions(in.threadId) = id
                        runs(id) = run
                  }

  /** what the daemon tells its pages: a step of a session on a run, its event; a call waiting, the run's interrupt */
  def told(event: String, data: Json): Unit = J.str(data, "session").flatMap(s => runs.get(s).map(s -> _)).foreach {
    (session, run) => event match
      // the words as they come: one message, each new piece of its text
      case "typing" => J.str(data, "text").foreach { t =>
          val (id, sent) = run.typing.getOrElse { val m = run.message(); run.emit(AgUi.textStart(m)); (m, 0) }
          if t.length > sent then run.emit(AgUi.textContent(id, t.substring(sent)))
          run.typing = Some((id, t.length))
        }
      case "step" => J.get(data, "step").foreach { st =>
          // a message streamed: its rest, and its end — its words not again whole
          val said = J.str(st, "k").contains("said")
          run.typing.foreach { (id, sent) =>
            val t = if said then J.str(st, "text").getOrElse("") else ""
            if t.length > sent then run.emit(AgUi.textContent(id, t.substring(sent)))
            run.emit(AgUi.textEnd(id))
          }
          val streamed = run.typing.isDefined && said
          run.typing = None
          if !streamed then AgUiBridge.events(st, run.message(), run.thread, run.id) match
            case (es, end) =>
              es.foreach(run.emit)
              if end then { runs.remove(session); run.stream.close() }
        }
      case "waiting" =>
        val call = J.str(data, "call").getOrElse("")
        val input = J.get(data, "input").getOrElse(Json.Obj.empty)
        val why = J.str(data, "why").getOrElse("")
        run.emit(AgUi.runInterrupted(run.thread, run.id, Vector(AgUi.approval(call,
          s"${J.str(data, "tool").getOrElse("tool")} ${Json.print(input).take(300)}${if why.isEmpty then "" else
            s" — $why"}", Some(call)))))
        runs.remove(session)
        run.stream.close()
      case _ => ()
  }

object AgUiBridge:
  /** any origin may run a session's agent — with the token (in `?t=` or `x-okay-token`): the token is the secret */
  val cors: List[(String, String)] = List("Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Headers" -> "content-type, x-okay-token, accept",
    "Access-Control-Allow-Methods" -> "POST, OPTIONS")

  /** a session's step as AG-UI events, and whether the run ends with it */
  def events(step: Json, messageId: String, thread: String, runId: String): (Vector[Json], Boolean) =
    def s(k: String) = J.str(step, k).getOrElse("")
    J.str(step, "k").getOrElse("") match
      case "said" => (AgUi.text(messageId, s("text")), false)
      case "tool" => (AgUi.toolCall(s("id"), s("name"), J.get(step, "input").getOrElse(Json.Obj.empty)), false)
      case "result" => (Vector(AgUi.toolResult(messageId, s("id"),
          (if J.bool(step, "error") then "error: " else "") + s("text").take(4000))), false)
      case "decided" => (Vector(AgUi.custom("okay.decided", Json.obj("toolCallId" -> Json.Str(s("id")),
          "verdict" -> Json.Str(s("verdict")), "why" -> Json.Str(s("why"))))), false)
      case "ended" =>
        val words = if J.bool(step, "ok") then Vector.empty else AgUi.text(messageId, s"(${s("text")})")
        (words :+ AgUi.runFinished(thread, runId), true)
      case "stopped" => (Vector(AgUi.runError(s("why"))), true)
      case _ => (Vector.empty, false)
