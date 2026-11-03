package okay.remote

import okay.effects.json.Json

/** a session as the list shows it */
final case class Session(id: String, project: String, model: String, title: String, agent: String = "",
  created: Long = 0, updated: Long = 0, running: Boolean = false, busy: Boolean = false, waiting: Int = 0):
  def toJson: Json = Json.Obj(Vector("id" -> Json.Str(id), "project" -> Json.Str(project),
    "model" -> Json.Str(model), "title" -> Json.Str(title), "agent" -> Json.Str(agent),
    "created" -> Json.num(created), "updated" -> Json.num(updated), "running" -> Json.Bool(running),
    "busy" -> Json.Bool(busy), "waiting" -> Json.num(waiting)))

object Session:
  import J.*
  def fromJson(j: Json): Option[Session] = str(j, "id").map(id => Session(id, str(j, "project").getOrElse(""),
    str(j, "model").getOrElse(""), str(j, "title").getOrElse(""), str(j, "agent").getOrElse(""),
    long(j, "created"), long(j, "updated"), bool(j, "running"), bool(j, "busy"), long(j, "waiting").toInt))
