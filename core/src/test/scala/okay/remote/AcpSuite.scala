package okay.remote

import okay.effects.json.{Descent, Json}
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class AcpSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.parasitic

  def place() = MemoryWorld({
    case "/h/work" => List("a")
    case "/h/work/a" => List(".git")
    case _ => Nil
  }, _ != "rozum")

  def method(j: Json) = J.str(j, "method").getOrElse("")
  def idOf(j: Json) = J.get(j, "id").map(x => Json.print(x)).getOrElse("")
  def result(id: String, r: String) = s"""{"jsonrpc":"2.0","id":$id,"result":$r}"""
  def request(id: Int, m: String, p: String) = s"""{"jsonrpc":"2.0","id":$id,"method":"$m","params":$p}"""
  def update(u: String) = s"""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$u}}"""

  test("an agent over ACP: its files and commands through the policy and the phone, its words the steps") {
    val m = place()
    val told = m.told
    val r = Remote(m.ports, () => 7788, Judge(_))
    r.start()
    var answered: Either[String, Json] = Left("")
    r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"), "model" -> Json.Str("acp:qwen"),
      "task" -> Json.Str("add a flag")))(answered = _)
    assert(answered.isRight, answered)
    val agent = m.procs.head
    assertEquals(agent.command, List("qwen", "--acp", "--approval-mode", "default"))
    assertEquals(agent.dir, "/h/work/a")
    // the handshake: we say we read and write the files and run the terminals
    val init = agent.json.head
    assertEquals(method(init), "initialize")
    assert(Json.print(init).contains("\"terminal\":true"))
    agent.say(result(idOf(init), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}"""))
    val fresh = agent.json.last
    assertEquals(method(fresh), "session/new")
    agent.say(result(idOf(fresh), """{"sessionId":"s1"}"""))
    val prompt = agent.json.last
    assertEquals(method(prompt), "session/prompt")
    assert(Json.print(prompt).contains("add a flag"))

    // a file written in the project: allowed, written here
    agent.say(update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Write","kind":"edit",""" +
      """"rawInput":{"file_path":"/h/work/a/x.txt"}}"""))
    agent.say(request(5, "fs/write_text_file", """{"sessionId":"s1","path":"/h/work/a/x.txt","content":"hi"}"""))
    assertEquals(m.saved.get("/h/work/a/x.txt"), Some("hi"))
    assertEquals(idOf(agent.json.last), "5")

    // a command: asked of the phone; approved, run here; its output given to the agent
    agent.say(request(6, "terminal/create", """{"sessionId":"s1","command":"python3 x.py"}"""))
    assert(told.exists(_._1 == "waiting"))
    assertEquals(m.procs.size, 1)
    val call = J.str(told.filter(_._1 == "waiting").last._2, "call").get
    r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str(call), "approve" -> Json.Bool(true)))(_ => ())
    val term = m.procs.last
    assertEquals(term.command, List("/bin/sh", "-c", "python3 x.py 2>&1"))
    val tid = J.get(agent.json.last, "result").flatMap(J.str(_, "terminalId")).get
    term.say("42"); term.onEnd("ended (0)")
    agent.say(request(7, "terminal/output", s"""{"sessionId":"s1","terminalId":"$tid"}"""))
    val out = J.get(agent.json.last, "result").get
    assertEquals(J.str(out, "output"), Some("42\n"))
    assertEquals(J.get(out, "exitStatus").map(J.long(_, "exitCode")), Some(0L))

    // a command the policy denies: not run, the agent told why
    agent.say(request(8, "terminal/create", """{"sessionId":"s1","command":"sudo rm -rf /tmp/x"}"""))
    assertEquals(m.procs.size, 2)
    assert(Json.print(agent.json.last).contains("nothing as root"), agent.json.last)

    // what the agent asks itself: refused on the phone, the reject option chosen
    agent.say(request(9, "session/request_permission", """{"sessionId":"s1","toolCall":{"toolCallId":"t9",""" +
      """"kind":"fetch","title":"Fetch","rawInput":{"url":"https://x"}},"options":[""" +
      """{"optionId":"yes","name":"Allow","kind":"allow_once"},""" +
      """{"optionId":"no","name":"Reject","kind":"reject_once"}]}"""))
    r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t9"), "approve" -> Json.Bool(false)))(_ => ())
    assertEquals(J.get(agent.json.last, "result").flatMap(J.get(_, "outcome")).flatMap(J.str(_, "optionId")),
      Some("no"))

    // its words, the end of the turn
    agent.say(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Do"}}"""))
    agent.say(update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"ne."}}"""))
    agent.say(result(idOf(prompt), """{"stopReason":"end_turn"}"""))
    val steps = told.collect { case ("step", j) => J.get(j, "step").flatMap(Step.fromJson).get }.toList
    assert(steps.contains(Step.Said("Done.")), steps)
    assert(steps.exists { case Step.Ended(true, _, _, _, _) => true; case _ => false })
    assert(steps.contains(Step.Decided("t1", "allow", "")))
    assert(steps.exists { case Step.Decided("t9", "refused", _) => true; case _ => false })
  }

  test("a permission approved is not asked again when the agent comes to write the file") {
    val m = place()
    val r = Remote(m.ports, () => 7788, Judge(_))
    r.start()
    r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"), "model" -> Json.Str("acp:qwen"),
      "task" -> Json.Str("go")))(_ => ())
    val agent = m.procs.head
    agent.say(result(idOf(agent.json.head), """{"agentCapabilities":{}}"""))
    agent.say(result(idOf(agent.json.last), """{"sessionId":"s1"}"""))
    // outside the project: the policy asks; approved once — the write that follows is not asked again
    agent.say(request(3, "session/request_permission", """{"sessionId":"s1","toolCall":{"toolCallId":"e1",""" +
      """"kind":"edit","locations":[{"path":"/h/elsewhere/y.txt"}]},"options":[{"optionId":"ok",""" +
      """"name":"Allow","kind":"allow_once"}]}"""))
    r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("e1"), "approve" -> Json.Bool(true)))(_ => ())
    agent.say(request(4, "fs/write_text_file", """{"sessionId":"s1","path":"/h/elsewhere/y.txt","content":"y"}"""))
    assertEquals(m.saved.get("/h/elsewhere/y.txt"), Some("y"))
  }
