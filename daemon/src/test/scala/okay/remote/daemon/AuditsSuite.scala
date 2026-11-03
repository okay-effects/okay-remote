package okay.remote.daemon

import okay.effects.json.Json
import okay.guard.policy.{Act, Outcome}
import okay.remote.Audited

/** the audit in words: a call decided otherwise now marked; approved then is an ask */
class AuditsSuite extends munit.FunSuite:
  test("each call: then, now, the tool, its gist, how it ended, why; a change marked"):
    val ls = Act("Bash", Json.obj("command" -> Json.Str("ls")), Outcome.Ok)
    val push = Act("Bash", Json.obj("command" -> Json.Str("git push")), Outcome.Ok)
    val report = Audits.report(List("s1" -> Vector(Audited(ls, "allow", "ask", "three commands failed in a row"),
      Audited(push, "approved", "ask", "it leaves the machine"))))
    val lines = report.linesIterator.toList
    assertEquals(lines.head, "s1: 2 calls, 1 decided otherwise now")
    assert(lines(1).startsWith("≠   1 then allow") && lines(1).contains("— three commands failed in a row"), lines(1))
    assert(lines(2).startsWith("    2 then approved now ask   Bash git push (ok)"), lines(2))
