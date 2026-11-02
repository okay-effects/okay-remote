package okay.remote.daemon

import okay.effects.json.{Descent, Json}
import okay.guard.local.{Stream, Web, WebRequest}

/** a session as an AG-UI agent: a new thread a new session; its steps the run's events; a waiting call an interrupt
 * ending the run, the next run's resume its verdict; the thread's next words told to the same session */
class AgUiBridgeSuite extends munit.FunSuite:
  private final class Out extends Web:
    val text = new StringBuilder
    var closed = false
    def reply(status: Int, headers: List[(String, String)], body: String): Unit = { text ++= body; closed = true }
    def stream(headers: List[(String, String)]): Stream = new Stream:
      def write(t: String): Unit = text ++= t
      def closed(f: () => Unit): Unit = ()
      override def close(): Unit = Out.this.closed = true
    def events: Vector[Json] = text.toString.split("\n\n").toVector.filter(_.startsWith("data: "))
      .map(l => Descent.parse(l.stripPrefix("data: ")).toOption.get)
    def types: Vector[String] = events.map(e => e("type").string)

  private def post(b: AgUiBridge, body: String): Out =
    val o = Out()
    b.serve(WebRequest("POST", "/agui", Map.empty, Map.empty, body), o)
    o
  private def step(st: String) = Descent.parse(s"""{"session": "s1", "step": $st}""").toOption.get

  test("a new thread, a waiting call, its resume, the end; the next words to the same session"):
    val acts = scala.collection.mutable.Buffer.empty[Json]
    val b = AgUiBridge(body => answer => { acts += body; answer(Right(Json.obj("id" -> Json.Str("s1")))) })
    val first = post(b, """{"threadId": "t", "runId": "r1", "messages": [{"id": "1", "role": "user",
      "content": "do it"}], "forwardedProps": {"project": "/p", "model": "okay:a/b"}}""")
    assertEquals(acts.head("type").string, "new")
    assertEquals(acts.head("project").string, "/p")  // as it is: not /tmp
    b.told("step", step("""{"k": "tool", "id": "c1", "name": "Write", "input": {"file_path": "/p/a"}}"""))
    b.told("waiting", Descent.parse("""{"session": "s1", "call": "c1", "tool": "Write",
      "input": {"file_path": "/p/a"}, "why": "asks"}""").toOption.get)
    assertEquals(first.types, Vector("RUN_STARTED", "TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END",
      "RUN_FINISHED"))
    assert(first.closed)
    val interrupt = first.events.last("outcome")("interrupts").list.head
    assertEquals(interrupt("id").string, "c1")
    val second = post(b, """{"threadId": "t", "runId": "r2", "messages": [], "resume": [{"interruptId": "c1",
      "status": "resolved", "payload": {"approved": true}}]}""")
    assertEquals(acts.last("type").string, "verdict")
    assertEquals(acts.last("approve"), Json.Bool(true))
    b.told("step", step("""{"k": "result", "id": "c1", "text": "written", "error": false}"""))
    b.told("step", step("""{"k": "said", "text": "done"}"""))
    b.told("step", step("""{"k": "ended", "ok": true, "text": "", "ms": 1, "cost": 0, "turns": 1}"""))
    assertEquals(second.types, Vector("RUN_STARTED", "TOOL_CALL_RESULT", "TEXT_MESSAGE_START",
      "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"))
    assertEquals(second.events.last("runId").string, "r2")
    assert(second.closed)
    // a step of no run: not shown anywhere
    b.told("step", step("""{"k": "said", "text": "late"}"""))
    assert(!second.text.toString.contains("late"))
    val third = post(b, """{"threadId": "t", "runId": "r3", "messages": [{"id": "2", "role": "user",
      "content": "more"}]}""")
    assertEquals(acts.last("type").string, "say")
    assertEquals(acts.last("session").string, "s1")
    assertEquals(third.types, Vector("RUN_STARTED"))

  test("the words as they come: one message, its new pieces; the words whole after it not sent again"):
    val b = AgUiBridge(_ => answer => answer(Right(Json.obj("id" -> Json.Str("s1")))))
    val o = post(b, """{"threadId": "t", "runId": "r", "messages": [{"id": "1", "role": "user", "content": "x"}],
      "forwardedProps": {"project": "/p"}}""")
    def typing(t: String) = b.told("typing", Json.obj("session" -> Json.Str("s1"), "text" -> Json.Str(t)))
    typing("Let me"); typing("Let me look")
    b.told("step", step("""{"k": "said", "text": "Let me look."}"""))
    b.told("step", step("""{"k": "ended", "ok": true, "text": "", "ms": 1, "cost": 0, "turns": 1}"""))
    assertEquals(o.types, Vector("RUN_STARTED", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_CONTENT",
      "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END", "RUN_FINISHED"))
    assertEquals(o.events.filter(_("type").string == "TEXT_MESSAGE_CONTENT").map(_("delta").string).mkString,
      "Let me look.")

  test("a new thread with no project: refused, the run ended"):
    val b = AgUiBridge(_ => _ => fail("no act"))
    val o = post(b, """{"threadId": "t", "runId": "r", "messages": [{"id": "1", "role": "user", "content": "x"}]}""")
    assertEquals(o.types, Vector("RUN_STARTED", "RUN_ERROR"))
    assert(o.closed)
