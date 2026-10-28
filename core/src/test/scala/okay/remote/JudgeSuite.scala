package okay.remote

import okay.effects.json.Json
import scala.concurrent.ExecutionContext

class JudgeSuite extends munit.FunSuite:
  given ExecutionContext = ExecutionContext.parasitic
  val judge = Judge(Defaults.policy).fold(e => fail(s"the default policy: $e"), identity)
  def decide(tool: String, input: (String, String)*): String =
    var out = ""
    judge.decide("1", tool, Json.Obj(input.toVector.map((k, v) => k -> Json.Str(v))), "/w/p")((v, _) => out = v)
    out

  test("reading is free; writing in the project is, elsewhere asks") {
    assertEquals(decide("Read", "file_path" -> "/etc/passwd"), "allow")
    assertEquals(decide("Write", "file_path" -> "/w/p/a.txt", "content" -> "x"), "allow")
    assertEquals(decide("Edit", "file_path" -> "/w/q/a.txt", "old_string" -> "a", "new_string" -> "b"), "ask")
    assertEquals(decide("Write", "file_path" -> "/w/p2/a.txt"), "ask")
    assertEquals(decide("Write", "file_path" -> "/w/p/../q/a.txt"), "ask")
  }
  test("commands: harmless ones go, chains and pushes ask, root never") {
    assertEquals(decide("Bash", "command" -> "sbt -batch test", "description" -> "tests"), "allow")
    assertEquals(decide("Bash", "command" -> "sbt test && rm -rf x"), "ask")
    assertEquals(decide("Bash", "command" -> "git push origin master"), "ask")
    assertEquals(decide("Bash", "command" -> "sudo ls"), "deny")
    assertEquals(decide("Bash", "command" -> "python3 x.py"), "ask")
  }
  test("a tool no rule knows asks") {
    assertEquals(decide("mcp__x__y", "a" -> "b"), "ask")
    assertEquals(decide("WebFetch", "url" -> "https://x"), "ask")
  }
