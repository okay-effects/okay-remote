package okay.remote

import okay.effects.json.Json
import scala.collection.mutable
import scala.concurrent.ExecutionContext

class RemoteSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.parasitic

  type Fake = MemoryWorld
  val dirs: String => List[String] = {
    case "/h/work" => List("a", "b")
    case "/h/work/a" => List(".git", "src")
    case "/h/work/b" => List("x")
    case "/h/work/b/x" => List(".git")
    case _ => Nil
  }

  val records = mutable.Buffer.empty[Json]
  val policies = mutable.Map.empty[String, String]
  val audit: Audit = new Audit:
    def record(r: Json): Unit = records += r
    def policy(hash: String, text: String): Unit = policies(hash) = text

  def made(jail: Jail = Jail()): (Fake, Remote, mutable.Buffer[(String, Json)]) =
    val m = MemoryWorld(dirs, n => n == "claude" || (jail.rozum && n == "rozum"))
    jail.claudeToken.foreach(t => m.files("/h/.okay-remote/claude-token") = t)
    records.clear()
    val r = Remote(m.ports, () => 7788, Judge(_), audit)
    r.start()
    (m, r, m.told)

  extension (r: Remote)
    def act1(j: Json): Either[String, Json] =
      var out: Either[String, Json] = Left("not answered")
      r.act(j)(out = _)
      out

  test("the projects are the repositories under the roots") {
    val (m, r, _) = made()
    assertEquals(m.value(r.projects), List("/h/work/a", "/h/work/b/x"))
  }

  test("a session: the agent started in its project, the task sent, its lines steps") {
    val (m, r, told) = made()
    val s = r.act1(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
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
    val opened = r.act1(Json.obj("type" -> Json.Str("open"), "session" -> Json.Str(id))).toOption.get
    assertEquals(J.arr(opened, "steps").size, 4)
    assert(m.files(s"/h/.okay-remote/sessions/$id.json").contains("cs-1"))
  }

  test("the hook: denied at once, asked of the phone and approved, a wrong key refused") {
    val (m, r, told) = made()
    val id = J.str(r.act1(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
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
    assert(r.act1(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t3"),
      "approve" -> Json.Bool(true))).isRight)
    assertEquals(out.toList.map(decision), List(Some("allow")))
    // answered twice: the second finds nothing
    assert(r.act1(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t3"))).isLeft)
    val wrong = mutable.Buffer.empty[Either[(Int, String), Json]]
    r.hook(id, "x" * key.length, Json.obj("tool_name" -> Json.Str("Bash")))(wrong += _)
    assertEquals(wrong.toList, List(Left((403, "not this session's"))))
  }

  test("an ask no one answers is refused when its time is up; a stopped agent refuses what waits") {
    val (m, r, _) = made()
    val id = J.str(r.act1(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
      "task" -> Json.Str("go"))).toOption.get, "id").get
    val key = m.spawned.head._1.find(_.contains("?k=")).get.split("\\?k=")(1).takeWhile(_ != ' ')
    val out = mutable.Buffer.empty[String]
    r.hook(id, key, Json.obj("tool_use_id" -> Json.Str("t"), "tool_name" -> Json.Str("WebFetch"),
      "tool_input" -> Json.obj("url" -> Json.Str("https://x"))))(o => out += o.toString)
    m.timers.foreach(_())
    assert(out.head.contains("deny") && out.head.contains("not done"), out)
  }

  test("a policy that does not compile is not taken") {
    val (_, r, _) = made()
    assert(r.act1(Json.obj("type" -> Json.Str("policy"), "text" -> Json.Str("policy x\nallow Foo when"))).isLeft)
    assertEquals(r.policy, Defaults.policy)
  }

  def started(r: Remote, m: Fake, task: String = "go"): (String, String) =
    val id = J.str(r.act1(Json.obj("type" -> Json.Str("new"), "project" -> Json.Str("/h/work/a"),
      "task" -> Json.Str(task))).toOption.get, "id").get
    (id, m.spawned.head._1.find(_.contains("?k=")).get.split("\\?k=")(1).takeWhile(_ != ' '))

  def asked(r: Remote, id: String, key: String, call: String, tool: String, input: (String, String)*)
    : mutable.Buffer[String] =
    val out = mutable.Buffer.empty[String]
    r.hook(id, key, Json.obj("tool_use_id" -> Json.Str(call), "tool_name" -> Json.Str(tool),
      "tool_input" -> Json.Obj(input.toVector.map((k, v) => k -> Json.Str(v)))))(o => out += o.toString)
    out

  test("always allow: the rule added, the call approved, the next one like it allowed at once") {
    val (m, r, _) = made()
    val (id, key) = started(r, m)
    val first = asked(r, id, key, "c1", "Bash", "command" -> "python3 x.py")
    assert(first.isEmpty)
    val got = r.act1(Json.obj("type" -> Json.Str("always"), "call" -> Json.Str("c1")))
    assert(got.isRight, got)
    assert(first.head.contains("allow"))
    assert(r.policy.contains("allow Bash when command = \"python3 x.py\""))
    assert(asked(r, id, key, "c2", "Bash", "command" -> "python3 x.py").head.contains("allow"))
    assert(asked(r, id, key, "c3", "Bash", "command" -> "python3 y.py").isEmpty)
  }

  test("always allow does not add what would not allow: an ask still wins") {
    val (m, r, _) = made()
    val (id, key) = started(r, m)
    asked(r, id, key, "c1", "Bash", "command" -> "make && make install")
    val got = r.act1(Json.obj("type" -> Json.Str("always"), "call" -> Json.Str("c1")))
    assert(got.left.exists(_.contains("a command made of commands")), got)
    assertEquals(r.policy, Defaults.policy)
  }

  test("changes: git status and diff of the session's project") {
    val (m, r, _) = made()
    val (id, _) = started(r, m)
    m.runAnswer = c => if c.contains("status") then (0, " M a.scala\n") else (0, "diff --git a/a.scala b/a.scala")
    val got = r.act1(Json.obj("type" -> Json.Str("diff"), "session" -> Json.Str(id))).toOption.get
    assertEquals(J.str(got, "status"), Some(" M a.scala\n"))
    assert(J.str(got, "diff").get.startsWith("diff --git"))
  }

  test("the record is a chain: verified; a line changed is found") {
    val (m, r, _) = made()
    val (id, _) = started(r, m)
    m.onLine("""{"type":"assistant","message":{"content":[{"type":"text","text":"On it."}]}}""")
    assertEquals(m.value(r.verify).map(_._2), List(Right(2)))
    val f = s"/h/.okay-remote/sessions/$id.jsonl"
    m.files(f) = m.files(f).replace("On it.", "Off it.")
    assert(m.value(r.verify).head._2.isLeft)
  }

  test("the jail: a local model in rozum; Claude in it only with a token, and the token in the environment") {
    val (m, r, _) = made(Jail(rozum = true, claudeToken = Some("tok")))
    started(r, m)
    assertEquals(m.spawned.head._1.take(4), List("rozum", "launch", "--no-model", "--"))
    assertEquals(m.envs.head.get("CLAUDE_CODE_OAUTH_TOKEN"), Some("tok"))
    assert(!m.spawned.head._1.exists(_.contains("tok")) || m.spawned.head._1.forall(_ != "tok"))
    val (m2, r2, _) = made(Jail(rozum = true))
    started(r2, m2)
    assertEquals(m2.spawned.head._1.head, "claude")
  }

  test("the journal is okay-guard's: each call a record — its decision with the proof, the person, the result") {
    val (m, r, _) = made()
    val (id, key) = started(r, m)
    asked(r, id, key, "t1", "Bash", "command" -> "sudo ls")
    asked(r, id, key, "t2", "Bash", "command" -> "python3 x.py")
    r.act1(Json.obj("type" -> Json.Str("verdict"), "call" -> Json.Str("t2"), "approve" -> Json.Bool(true)))
    m.onLine("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"42"}]}}""")
    def at(j: Json, path: String*) = okay.guard.Paths.at(j, path.toList).getOrElse(Json.Null)
    assertEquals(records.map(r => (at(r, "action", "operation"), at(r, "decision"), at(r, "outcome", "status"))).toList,
      List((Json.Str("Bash"), Json.Str("block"), Json.Str("not done")),
        (Json.Str("Bash"), Json.Str("ask"), Json.Str("ok"))))
    assertEquals(at(records.last, "person", "verdict"), Json.Str("approved"))
    assertEquals(at(records.last, "outcome", "result"), Json.Str("42"))
    assertEquals(at(records.last, "action", "service"), Json.Str("claude"))
    assertEquals(at(records.last, "agent"), Json.Str(id))
    // the policy each record was decided by, kept by its hash
    val hash = at(records.last, "policy", "hash")
    assert(hash.isInstanceOf[Json.Str] && policies.contains(hash.asInstanceOf[Json.Str].s))
    assert(at(records.head, "why", "certificate") != Json.Null || at(records.head, "why") != Json.Null)
  }

