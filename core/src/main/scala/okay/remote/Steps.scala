package okay.remote

import okay.effects.json.{Descent, Json}

/** A STEP OF A SESSION, as the page shows it: what you said, what the agent said, a tool it called and what the
 * policy decided, the tool's result, the end of a turn. Kept one per line in the session's file; told to the page */
enum Step:
  case You(text: String)
  case Began(agentSession: String, model: String)
  case Said(text: String)
  case Tool(id: String, name: String, input: Json)
  /** the policy's word on a tool call: `allow`, `deny`, `ask`; then a person's `approved`, `refused`, `timed out` */
  case Decided(id: String, verdict: String, why: String)
  case Result(id: String, text: String, error: Boolean)
  case Ended(ok: Boolean, text: String, ms: Long, cost: Double, turns: Int)
  /** the agent's process gone: its code, or why it could not start */
  case Stopped(why: String)

object Step:
  import J.*

  def toJson(s: Step): Json = s match
    case You(t) => obj("k" -> "you", "text" -> t)
    case Began(a, m) => obj("k" -> "began", "agent" -> a, "model" -> m)
    case Said(t) => obj("k" -> "said", "text" -> t)
    case Tool(i, n, in) => Json.Obj(Vector("k" -> Json.Str("tool"), "id" -> Json.Str(i), "name" -> Json.Str(n),
        "input" -> in))
    case Decided(i, v, w) => obj("k" -> "decided", "id" -> i, "verdict" -> v, "why" -> w)
    case Result(i, t, e) => Json.Obj(Vector("k" -> Json.Str("result"), "id" -> Json.Str(i), "text" -> Json.Str(t),
        "error" -> Json.Bool(e)))
    case Ended(ok, t, ms, c, n) => Json.Obj(Vector("k" -> Json.Str("ended"), "ok" -> Json.Bool(ok),
        "text" -> Json.Str(t), "ms" -> Json.num(ms), "cost" -> Json.Num(BigDecimal(c)), "turns" -> Json.num(n)))
    case Stopped(w) => obj("k" -> "stopped", "why" -> w)

  def fromJson(j: Json): Option[Step] =
    def s(k: String) = str(j, k).getOrElse("")
    str(j, "k").collect {
      case "you" => You(s("text"))
      case "began" => Began(s("agent"), s("model"))
      case "said" => Said(s("text"))
      case "tool" => Tool(s("id"), s("name"), get(j, "input").getOrElse(Json.Obj.empty))
      case "decided" => Decided(s("id"), s("verdict"), s("why"))
      case "result" => Result(s("id"), s("text"), bool(j, "error"))
      case "ended" => Ended(bool(j, "ok"), s("text"), long(j, "ms"), num(j, "cost"), long(j, "turns").toInt)
      case "stopped" => Stopped(s("why"))
    }

  /** A LINE OF CLAUDE CODE'S `--output-format stream-json`, as steps: its start, its words, its tool calls and their
   * results, the end of a turn. Everything else (hooks, thinking, rate limits) is none */
  def ofAgent(line: String): List[Step] = Descent.parse(line).toOption.toList.flatMap { j =>
    (str(j, "type"), str(j, "subtype")) match
      case (Some("system"), Some("init")) => List(Began(str(j, "session_id").getOrElse(""),
          str(j, "model").getOrElse("")))
      case (Some("assistant"), _) => content(j).flatMap { c =>
          str(c, "type") match
            case Some("text") => str(c, "text").filter(_.trim.nonEmpty).map(Said(_)).toList
            case Some("tool_use") => List(Tool(str(c, "id").getOrElse(""), str(c, "name").getOrElse("?"),
                get(c, "input").getOrElse(Json.Obj.empty)))
            case _ => Nil
        }
      case (Some("user"), _) => content(j).flatMap { c =>
          if str(c, "type").contains("tool_result") then
            List(Result(str(c, "tool_use_id").getOrElse(""), text(get(c, "content")), bool(c, "is_error")))
          else Nil
        }
      case (Some("result"), sub) => List(Ended(sub.contains("success") && !bool(j, "is_error"),
          str(j, "result").getOrElse(str(j, "subtype").getOrElse("")), long(j, "duration_ms"),
          num(j, "total_cost_usd"), long(j, "num_turns").toInt))
      case _ => Nil
  }

  private def content(j: Json): List[Json] = get(j, "message").flatMap(get(_, "content")) match
    case Some(Json.Arr(cs)) => cs.toList
    case _ => Nil

  /** a tool result's content: a text, or a list of parts (texts, images) */
  private def text(c: Option[Json]): String = c match
    case Some(Json.Str(t)) => t
    case Some(Json.Arr(parts)) => parts.flatMap(p => str(p, "text").orElse(str(p, "type").map(t => s"[$t]")))
        .mkString("\n")
    case Some(other) => Json.print(other)
    case None => ""

/** reading JSON, briefly */
object J:
  def get(j: Json, k: String): Option[Json] = j match
    case o: Json.Obj => o.get(k)
    case _ => None
  def str(j: Json, k: String): Option[String] = get(j, k).collect { case Json.Str(s) => s }
  def bool(j: Json, k: String): Boolean = get(j, k).contains(Json.Bool(true))
  def long(j: Json, k: String): Long = get(j, k).collect { case Json.Num(n) => n.toLong }.getOrElse(0L)
  def num(j: Json, k: String): Double = get(j, k).collect { case Json.Num(n) => n.toDouble }.getOrElse(0.0)
  def arr(j: Json, k: String): Vector[Json] = get(j, k).collect { case Json.Arr(a) => a }.getOrElse(Vector.empty)
  def obj(fields: (String, String)*): Json.Obj = Json.Obj(fields.toVector.map((k, v) => k -> Json.Str(v)))
