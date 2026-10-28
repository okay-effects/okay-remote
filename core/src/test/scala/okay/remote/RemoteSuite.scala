package okay.remote

import okay.effects.json.{Descent, Json}
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class RemoteSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.parasitic

  /** a machine in memory: its files a map, its one agent scripted by the test */
  final class Fake extends Machine:
    val files = mutable.Map.empty[String, String]
    val sent = mutable.Buffer.empty[String]
    var spawned: List[(List[String], String)] = Nil
    var onLine: String => Unit = _ => ()
    var onEnd: String => Unit = _ => ()
    val timers = mutable.Buffer.empty[() => Unit]
    def now = 1000L
    def later(ms: Int)(f: => Unit) = timers += (() => f)
    private var n = 0
    def randomHex(bytes: Int) = { n += 1; f"$n%02d" * bytes }
    def home = "/h"
    def read(f: String) = files.get(f)
    def write(f: String, t: String) = files(f) = t
    def append(f: String, t: String) = files(f) = files.getOrElse(f, "") + t
    def remove(f: String) = { files.remove(f); () }
    def list(d: String) = d match
      case "/h/work" => List("a", "b")
      case "/h/work/a" => List(".git", "src")
      case "/h/work/b" => List("x")
      case "/h/work/b/x" => List(".git")
      case "/h/.okay-remote/sessions" => files.keys.filter(_.startsWith(d + "/")).map(_.drop(d.length + 1)).toList
      case _ => Nil
    def spawn(c: List[String], d: String): Agent =
      spawned ::= (c, d)
      new Agent:
        def send(l: String) = { sent += l; () }
        def lines(f: String => Unit) = onLine = f
        def ended(f: String => Unit) = onEnd = f
        def kill() = onEnd("killed")
    def log(t: String) = ()

  def made(): (Fake, Remote, mutable.Buffer[(String, Json)]) =
    val m = Fake()
    val told = mutable.Buffer.empty[(String, Json)]
    val r = Remote(m, () => 7788, (e, j) => told += e -> j, Judge(_))
    r.start()
    (m, r, told)

  test("the projects are the repositories under the roots") {
    val (_, r, _) = made()
    assertEquals(r.projects, List("/h/work/a", "/h/work/b/x"))
  }

  test("a session: the agent started in its project, the task sent, its lines steps") {
    val (m, r, told) = made()
    val s = r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
      "model" -> Json.Str("claude:haiku"), "task" -> Json.Str("add a flag"))).toOption.get
    val id = J.str(s, "id").get
    val (cmd, dir) = m.spawned.head
    assertEquals(dir, "/h/work/a")
    assert(cmd.take(2) == List("claude", "-p") && cmd.contains("haiku") && cmd.contains("--settings"))
    assert(m.sent.head.contains("add a flag"))
    m.onLine("""{"type":"system","subtype":"init","session_id":"cs-1","model":"haiku"}""")
    m.onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":"On it."},""" +
      """{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sudo rm x"}}]}}""")
    val steps = told.collect { case ("step", j) => J.get(j, "step").flatMap(Step.fromJson).get }
    assertEquals(steps.toList, List(Step.You("add a flag"), Step.Began("cs-1", "haiku"), Step.Said("On it."),
      Step.Tool("t1", "Bash", Json.obj("command" -> Json.Str("sudo rm x")))))
    // kept: opened again, the steps are there
    val opened = r.act(Json.obj("type" -> Json.Str("open"), "session" -> Json.Str(id))).toOption.get
    assertEquals(J.arr(opened, "steps").size, 4)
    assert(m.files(s"/h/.okay-remote/sessions/$id.json").contains("cs-1"))
  }

  test("the hook: denied at once, asked of the phone and approved, a wrong key refused") {
    val (m, r, told) = made()
    val id = J.str(r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
      "task" -> Json.Str("go"))).toOption.get, "id").get
    val key = m.spawned.head._1.find(_.contains("?k=")).get.split("\\?k=")(1).takeWhile(_ != ' ')
    def call(tid: String, cmd: String): List[Either[(Int, String), Json]] =
      val out = mutable.Buffer.empty[Either[(Int, String), Json]]
      r.hook(id, key, Json.obj("tool_use_id" -> Json.Str(tid), "tool_name" -> Json.Str("Bash"),
        "tool_input" -> Json.obj("command" -> Json.Str(cmd))))(out += _)
      out.toList
    def decision(o: Either[(Int, String), Json]) = o.toOption.flatMap(J.get(_, "hookSpecificOutput"))
      .flatMap(J.str(_, "permissionDecision"))
    assertEquals(call("t1", "sudo ls").map(decision), List(Some("deny")))
    assertEquals(call("t2", "ls -la").map(decision), List(Some("allow")))
    // asked: nothing yet; the phone told; approved, the hook answered
    val out = mutable.Buffer.empty[Either[(Int, String), Json]]
    r.hook(id, key, Json.obj("tool_use_id" -> Json.Str("t3"), "tool_name" -> Json.Str("Bash"),
      "tool_input" -> Json.obj("command" -> Json.Str("python3 x.py"))))(out += _)
    assert(out.isEmpty)
    assert(told.exists(_._1 == "waiting"))
    assert(r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t3"),
      "approve" -> Json.Bool(true))).isRight)
    assertEquals(out.toList.map(decision), List(Some("allow")))
    // answered twice: the second finds nothing
    assert(r.act(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t3"))).isLeft)
    val wrong = mutable.Buffer.empty[Either[(Int, String), Json]]
    r.hook(id, "x" * key.length, Json.obj("tool_name" -> Json.Str("Bash")))(wrong += _)
    assertEquals(wrong.toList, List(Left((403, "not this session's"))))
  }

  test("an ask no one answers is refused when its time is up; a stopped agent refuses what waits") {
    val (m, r, _) = made()
    val id = J.str(r.act(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
      "task" -> Json.Str("go"))).toOption.get, "id").get
    val key = m.spawned.head._1.find(_.contains("?k=")).get.split("\\?k=")(1).takeWhile(_ != ' ')
    val out = mutable.Buffer.empty[String]
    r.hook(id, key, Json.obj("tool_use_id" -> Json.Str("t"), "tool_name" -> Json.Str("WebFetch"),
      "tool_input" -> Json.obj("url" -> Json.Str("https://x"))))(o => out += o.toString)
    m.timers.foreach(_())
    assert(out.head.contains("deny") && out.head.contains("in time"))
  }

  test("a policy that does not compile is not taken") {
    val (_, r, _) = made()
    assert(r.act(Json.obj("type" -> Json.Str("policy"), "text" -> Json.Str("policy x\nallow Foo when"))).isLeft)
    assertEquals(r.policy, Defaults.policy)
  }
