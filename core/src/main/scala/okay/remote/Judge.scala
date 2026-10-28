package okay.remote

import okay.effects.json.Json
import okay.guard.{Action, Decision}
import okay.guard.policy.{PolicyFiles, PolicyGuard}
import scala.concurrent.ExecutionContext

/** THE POLICY OVER AN AGENT'S TOOL CALLS (okay-guard's language): each call of Claude Code's tools an action — the
 * tool its operation, its input and `cwd` (the project's directory) its arguments — decided `allow`, `ask` or `deny`,
 * and why. A policy that does not compile is not made: fail closed is the caller's (`Judge.closed`) */
final class Judge private (val guard: PolicyGuard):
  def decide(id: String, tool: String, input: Json, cwd: String)(answer: (String, String) => Unit): Unit =
    // the project's directory with its `/`: `/w/p` must not take in `/w/p2`
    val dir = if cwd.endsWith("/") then cwd else cwd + "/"
    val args = input match
      case o: Json.Obj => Json.Obj(o.fields.filterNot(_._1 == "cwd") :+ ("cwd" -> Json.Str(dir)))
      case _ => Json.obj("cwd" -> Json.Str(dir))
    // the reason the rule gives; not the list of the rules that did not apply
    def first(why: String) = why.split("; ").headOption.getOrElse(why)
    guard.decide(Action(Json.Str(id), tool, args), j => j.decision match
      case Decision.Pass(_) => answer("allow", "")
      case Decision.Ask(_, why) => answer("ask", first(why))
      case Decision.Block(_, why) => answer("deny", first(why)))

object Judge:
  def apply(text: String)(using ExecutionContext): Either[String, Judge] =
    PolicyFiles.load(text).map(p => new Judge(new PolicyGuard(p)))

/** THE POLICY A MACHINE STARTS WITH: reading is free, writing in the project is, a command asks unless it is one of
 * the harmless ones; nothing as root, nothing that leaves the machine unasked */
object Defaults:
  val policy: String =
    """policy coding
      |
      |-- Claude Code's tools that change something, and what they are given (cwd: the project)
      |call Bash(command: Text, cwd: Text)
      |call Write(file_path: Text, cwd: Text)
      |call Edit(file_path: Text, cwd: Text)
      |call MultiEdit(file_path: Text, cwd: Text)
      |call NotebookEdit(notebook_path: Text, cwd: Text)
      |
      |-- reading and planning: always
      |allow * when tool in ["Read", "Glob", "Grep", "LS", "TodoWrite", "ToolSearch", "Task", "TaskStop",
      |  "TaskOutput", "Skill", "BashOutput", "KillShell", "ExitPlanMode", "EnterPlanMode"]
      |
      |-- files: in the project, yes; elsewhere, ask
      |allow Write when file_path starts cwd
      |allow Edit when file_path starts cwd
      |allow MultiEdit when file_path starts cwd
      |allow NotebookEdit when notebook_path starts cwd
      |ask Write when file_path contains ".." because "a path that climbs out"
      |ask Edit when file_path contains ".." because "a path that climbs out"
      |ask MultiEdit when file_path contains ".." because "a path that climbs out"
      |
      |-- commands: the harmless ones go, the rest asks
      |allow Bash when command starts "ls" or command starts "cat " or command starts "pwd" or
      |  command starts "git status" or command starts "git diff" or command starts "git log" or
      |  command starts "sbt " or command starts "cargo " or command starts "npm test" or command starts "grep " or
      |  command starts "head " or command starts "tail " or command starts "wc " or command starts "find "
      |ask Bash when command contains "&&" or command contains "|" or command contains ";" or command contains "`"
      |  or command contains "$(" because "a command made of commands"
      |ask Bash when command contains "git push" because "it leaves the machine"
      |deny Bash when command contains "sudo" because "nothing as root"
      |deny Bash when command contains "rm -rf /" or command contains "rm -rf ~" because "not the whole disk"
      |
      |-- the web: ask
      |ask * when tool in ["WebFetch", "WebSearch"] because "it reaches the internet"
      |
      |default ask
      |""".stripMargin
