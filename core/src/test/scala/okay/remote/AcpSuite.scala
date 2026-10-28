package okay.remote

import okay.effects.json.{Descent, Json}
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class AcpSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.parasitic

  /** a process the test speaks for */
  final class Proc(val command: List[String], val dir: String):
    val sent = mutable.Buffer.empty[Json]
    var onLine: String => Unit = _ => ()
    var onEnd: String => Unit = _ => ()
    var killed = false
    val agent: Agent = new Agent:
      def send(l: String) = Descent.parse(l).foreach(sent += _)
      def lines(f: String => Unit) = onLine = f
      def ended(f: String => Unit) = onEnd = f
      def kill() = killed = true
    def say(j: String) = onLine(j)

  final class Fake extends Machine:
    val files = mutable.Map.empty[String, String]
    val saved = mutable.Map.empty[String, String]
    val procs = mutable.Buffer.empty[Proc]
    def now = 1000L
    def later(ms: Int)(f: => Unit) = ()
    private var n = 0
    def randomHex(bytes: Int) = { n += 1; f"$n%02x" * bytes }
    def home = "/h"
    def read(f: String) = files.get(f).orElse(saved.get(f))
    def write(f: String, t: String) = files(f) = t
    def save(f: String, t: String) = saved(f) = t
    def onPath(name: String) = true
    def append(f: String, t: String) = files(f) = files.getOrElse(f, "") + t
    def remove(f: String) = { files.remove(f); () }
    def list(d: String) = d match
      case "/h/work" => List("a")
      case "/h/work/a" => List(".git")
      case _ => Nil
    def real(p: String) = p
    def spawn(c: List[String], d: String, env: Map[String, String]): Agent =
      val p = Proc(c, d); procs += p; p.agent
    def run(c: List[String], d: String, limit: Int)(done: (Int, String) => Unit) = done(0, "")
    def log(t: String) = ()

  def method(j: Json) = J.str(j, "method").getOrElse("")
  def idOf(j: Json) = J.get(j, "id").map(x => Json.print(x)).getOrElse("")
  def result(id: String, r: String) = s"""{"jsonrpc":"2.0","id":$id,"result":$r}"""
  def request(id: Int, m: String, p: String) = s"""{"jsonrpc":"2.0","id":$id,"method":"$m","params":$p}"""
  def update(u: String) = s"""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":$u}}"""

  test("an agent over ACP: its files and commands through the policy and the phone, its words the steps") {
    val m = Fake()
    val told = mutable.Buffer.empty[(String, Json)]
    val r = Remote(m, () => 7788, (e, j) => told += e -> j, Judge(_))
    r.start()
    var answered: Either[String, Json] = Left("")
    r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"), "model" -> Json.Str("acp:qwen"),
      "task" -> Json.Str("add a flag")))(answered = _)
    assert(answered.isRight, answered)
    val agent = m.procs.head
    assertEquals(agent.command, List("qwen", "--acp", "--approval-mode", "default"))
    assertEquals(agent.dir, "/h/work/a")
    // the handshake: we say we read and write the files and run the terminals
    val init = agent.sent.head
    assertEquals(method(init), "initialize")
    assert(Json.print(init).contains("\"terminal\":true"))
    agent.say(result(idOf(init), """{"protocolVersion":1,"agentCapabilities":{"loadSession":true}}"""))
    val fresh = agent.sent.last
    assertEquals(method(fresh), "session/new")
    agent.say(result(idOf(fresh), """{"sessionId":"s1"}"""))
    val prompt = agent.sent.last
    assertEquals(method(prompt), "session/prompt")
    assert(Json.print(prompt).contains("add a flag"))

    // a file written in the project: allowed, written here
    agent.say(update("""{"sessionUpdate":"tool_call","toolCallId":"t1","title":"Write","kind":"edit",""" +
      """"rawInput":{"file_path":"/h/work/a/x.txt"}}"""))
    agent.say(request(5, "fs/write_text_file", """{"sessionId":"s1","path":"/h/work/a/x.txt","content":"hi"}"""))
    assertEquals(m.saved.get("/h/work/a/x.txt"), Some("hi"))
    assertEquals(idOf(agent.sent.last), "5")

    // a command: asked of the phone; approved, run here; its output given to the agent
    agent.say(request(6, "terminal/create", """{"sessionId":"s1","command":"python3 x.py"}"""))
    assert(told.exists(_._1 == "waiting"))
    assertEquals(m.procs.size, 1)
    val call = J.str(told.filter(_._1 == "waiting").last._2, "call").get
    r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str(call), "approve" -> Json.Bool(true)))(_ => ())
    val term = m.procs.last
    assertEquals(term.command, List("/bin/sh", "-c", "python3 x.py 2>&1"))
    val tid = J.get(agent.sent.last, "result").flatMap(J.str(_, "terminalId")).get
    term.say("42"); term.onEnd("ended (0)")
    agent.say(request(7, "terminal/output", s"""{"sessionId":"s1","terminalId":"$tid"}"""))
    val out = J.get(agent.sent.last, "result").get
    assertEquals(J.str(out, "output"), Some("42\n"))
    assertEquals(J.get(out, "exitStatus").map(J.long(_, "exitCode")), Some(0L))

    // a command the policy denies: not run, the agent told why
    agent.say(request(8, "terminal/create", """{"sessionId":"s1","command":"sudo rm -rf /tmp/x"}"""))
    assertEquals(m.procs.size, 2)
    assert(Json.print(agent.sent.last).contains("nothing as root"), agent.sent.last)

    // what the agent asks itself: refused on the phone, the reject option chosen
    agent.say(request(9, "session/request_permission", """{"sessionId":"s1","toolCall":{"toolCallId":"t9",""" +
      """"kind":"fetch","title":"Fetch","rawInput":{"url":"https://x"}},"options":[""" +
      """{"optionId":"yes","name":"Allow","kind":"allow_once"},""" +
      """{"optionId":"no","name":"Reject","kind":"reject_once"}]}"""))
    r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t9"), "approve" -> Json.Bool(false)))(_ => ())
    assertEquals(J.get(agent.sent.last, "result").flatMap(J.get(_, "outcome")).flatMap(J.str(_, "optionId")),
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
    val m = Fake()
    val r = Remote(m, () => 7788, (_, _) => (), Judge(_))
    r.start()
    r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"), "model" -> Json.Str("acp:qwen"),
      "task" -> Json.Str("go")))(_ => ())
    val agent = m.procs.head
    agent.say(result(idOf(agent.sent.head), """{"agentCapabilities":{}}"""))
    agent.say(result(idOf(agent.sent.last), """{"sessionId":"s1"}"""))
    // outside the project: the policy asks; approved once — the write that follows is not asked again
    agent.say(request(3, "session/request_permission", """{"sessionId":"s1","toolCall":{"toolCallId":"e1",""" +
      """"kind":"edit","locations":[{"path":"/h/elsewhere/y.txt"}]},"options":[{"optionId":"ok",""" +
      """"name":"Allow","kind":"allow_once"}]}"""))
    r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("e1"), "approve" -> Json.Bool(true)))(_ => ())
    agent.say(request(4, "fs/write_text_file", """{"sessionId":"s1","path":"/h/elsewhere/y.txt","content":"y"}"""))
    assertEquals(m.saved.get("/h/elsewhere/y.txt"), Some("y"))
  }
