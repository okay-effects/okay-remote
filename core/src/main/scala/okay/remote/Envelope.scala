package okay.remote

import okay.effects.json.{Descent, Json}
import scala.collection.mutable

/**
 * WHAT A RELAY FRAME SAYS, once opened (specs/remote.md, "Anywhere: the relay"): who sent it, when, a nonce, its kind
 * and its fields. Sealing (AES-GCM) is the platform's; this is the part both ends share: a frame from the wrong side,
 * too old or too new, or seen before, is not taken — no reflection, no replay.
 */
object Envelope:
  val windowMs: Long = 5 * 60 * 1000

  def wrap(from: String, now: Long, nonce: String, kind: String, fields: (String, Json)*): String =
    Json.print(Json.Obj(Vector("v" -> Json.num(1), "from" -> Json.Str(from), "t" -> Json.num(now),
      "n" -> Json.Str(nonce), "kind" -> Json.Str(kind)) ++ fields))

  /** the nonces seen within the window, by when they came: one per receiving end */
  final class Seen:
    private val at = mutable.LinkedHashMap.empty[String, Long]
    def fresh(n: String, now: Long): Boolean =
      at.filterInPlace((_, t) => now - t <= 2 * windowMs)
      if n.length < 8 || at.contains(n) then false else { at(n) = now; true }

  /** a frame's text opened: its kind and the whole JSON — if it is from `from`, in time, and new */
  def open(text: String, from: String, now: Long, seen: Seen): Either[String, (String, Json)] =
    Descent.parse(text).left.map(_ => "not JSON").flatMap { j =>
      val t = J.long(j, "t")
      if !J.str(j, "from").contains(from) then Left("from the wrong side")
      else if math.abs(now - t) > windowMs then Left("out of time")
      else if !seen.fresh(J.str(j, "n").getOrElse(""), now) then Left("seen before")
      else J.str(j, "kind").toRight("no kind").map(_ -> j)
    }

/** THE SECRET A PHONE IS PAIRED BY, and what is derived from it: the key, the room's auth, the room */
object Pairing:
  def encLabel: String = "okay-remote/enc:"
  def authLabel: String = "okay-remote/auth:"
  val aad: String = "okay-remote/1"
