package okay.remote

import okay.effects.json.Json

/** A RULE MADE OF A CALL, for "always allow": as narrow as says what was asked — a command exactly, a file's
 * directory, a tool by its name — added at the end of the policy, with the declaration it needs */
object Rules:
  private def quoted(s: String): String = okay.policy.Printer.quote(s)

  def rule(tool: String, input: Json): String =
    def arg(k: String) = J.str(input, k).getOrElse("")
    def dir(path: String) = path.reverse.dropWhile(_ != '/').reverse
    tool match
      case "Bash" => s"allow Bash when command = ${quoted(arg("command"))}"
      case "Write" | "Edit" | "MultiEdit" => s"allow $tool when file_path starts ${quoted(dir(arg("file_path")))}"
      case "NotebookEdit" => s"allow NotebookEdit when notebook_path starts ${quoted(dir(arg("notebook_path")))}"
      case other => s"allow * when tool = ${quoted(other)}"

  def added(policy: String, tool: String, input: Json): String =
    val line = rule(tool, input)
    policy.stripSuffix("\n") + s"\n\n-- always allowed, from a call\n$line\n"
