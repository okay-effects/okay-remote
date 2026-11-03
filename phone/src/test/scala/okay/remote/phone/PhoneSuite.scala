package okay.remote.phone

import okay.effects.json.Json
import okay.faces.mobile.{Device, Sealing}
import okay.remote.{J, Session, Step}
import okay.serverless.relay.{Envelope, Hashes, Pairing}

/** THE PHONE ON THE JVM: the page's program on the mobile face, its phone a `Device.Memory` whose sockets reach a
 * room in this process — a daemon there, sealing as the real one does */
class PhoneSuite extends munit.FunSuite:
  val pairing = Pairing(Array.tabulate[Byte](32)(i => (i * 7 + 1).toByte), "okay-remote")
  val link = s"https://relay.example/#s=${pairing.text}"
  val key = Hashes.base64url(pairing.enc)

  /** THE ROOM: what the guest sent, the daemon's answers to it; nothing delivered until `pump` — a socket's words come
   * after its opening, on the next turn */
  final class Room:
    var dialled = List.empty[String]
    private var toGuest: String => Unit = _ => ()
    private val queue = scala.collection.mutable.Queue.empty[String]
    val got = scala.collection.mutable.ListBuffer.empty[(String, Json)]
    private val seen = Envelope.Seen()
    private var n = 0
    var answer: Json => Json = _ => Json.Obj.empty
    val sockets: (String, String => Unit) => Option[String => Unit] = (url, to) =>
      dialled :+= url
      toGuest = to
      Some(frame => Sealing.unseal(key, pairing.aad, frame).foreach(text =>
        Envelope.open(text, "guest", System.currentTimeMillis(), seen).foreach { (kind, j) =>
          got += kind -> j
          kind match
            case "hello" => event("hello", Json.obj("projects" -> Json.Arr(Vector(Json.Str("/work/demo"))),
                "sessions" -> Json.Arr(Vector(Session("s1", "/work/demo", "claude", "Fix the bug", running = true)
                  .toJson))))
            case "act" => host("answer", "id" -> J.get(j, "id").getOrElse(Json.Null), "ok" -> Json.Bool(true),
                "body" -> answer(J.get(j, "body").getOrElse(Json.Obj.empty)))
            case _ => ()
        }))
    def say(text: String): Unit = queue += text
    def event(name: String, data: Json): Unit = host("event", "name" -> Json.Str(name), "data" -> data)
    private def host(kind: String, fields: (String, Json)*): Unit =
      n += 1
      say(Sealing.seal(key, pairing.aad, Envelope.wrap("host", System.currentTimeMillis(), f"host-nonce-$n%04d", kind,
        fields*)))
    def pump(): Unit = while queue.nonEmpty do toGuest(queue.dequeue())

  def phone(room: Room) = new Device.Memory(sockets = room.sockets)

  test("paired: the room dialled by the pairing, the daemon's hello its sessions, an act answered"):
    val room = Room()
    val memory = phone(room)
    memory.hidden("okay-remote.relay") = pairing.text
    memory.hidden("okay-remote.relay-origin") = "https://relay.example"
    val d = RemoteApp.over(memory)
    var moved = 0
    d.onMoved(() => moved += 1)
    assert(d.open().contains("connecting"), d.open())
    assertEquals(room.dialled, List(s"wss://relay.example/r/${pairing.room}/guest?auth=${pairing.auth}"))
    room.say("online")
    room.pump()
    assertEquals(room.got.map(_._1).toList, List("hello"))
    room.pump()
    val listed = d.open()
    assert(listed.contains("Fix the bug") && listed.contains("connected"), listed)
    assert(moved > 0)
    // the session opened: an act through the room, its answer the session's steps
    room.answer = body => if J.str(body, "type").contains("open") then
      Json.obj("steps" -> Json.Arr(Vector(Step.toJson(Step.Said("Found it: an off-by-one.")))))
    else Json.Obj.empty
    val ref = "\"(open-s1/[^\"]*)\"".r.findFirstMatchIn(listed).map(_.group(1)).getOrElse(fail(listed))
    d.act(Json.print(Json.obj("action" -> Json.Str("ref"), "form" -> Json.obj("key" -> Json.Str(ref)))))
    room.pump()
    assert(room.got.exists((k, j) => k == "act" && J.get(j, "body").flatMap(J.str(_, "type")).contains("open")))
    room.pump()
    assert(d.open().contains("Found it: an off-by-one."), d.open())

  test("frames not sealed by the pairing are dropped; the room going offline is shown"):
    val room = Room()
    val memory = phone(room)
    memory.hidden("okay-remote.relay") = pairing.text
    val d = RemoteApp.over(memory)
    assert(room.dialled.head.startsWith("wss://okay-remote.okay-effects.workers.dev/r/"))
    room.say("online")
    room.pump()
    val other = Pairing(Array.fill[Byte](32)(9), "okay-remote")
    room.say(Sealing.seal(Hashes.base64url(other.enc), other.aad, Envelope.wrap("host", System.currentTimeMillis(),
      "forged-nonce", "event", "name" -> Json.Str("forgot"), "data" -> Json.Str("s1"))))
    room.pump()
    assert(d.open().contains("Fix the bug"))
    room.say("offline")
    room.pump()
    assert(d.open().contains("the machine is not reached"), d.open())

  test("not paired: the page says how; a pasted link pairs, kept in the keychain, and the room dialled"):
    val room = Room()
    val memory = phone(room)
    val d = RemoteApp.over(memory)
    val first = d.open()
    assert(first.contains("not paired"), first)
    assert(room.dialled.isEmpty)
    assert(first.contains("\"name\":\"pair\""), first)
    val field = "or-paste-a-pairing-link"
    assert(first.contains(s"\"$field\""), first)
    d.act(Json.print(Json.obj("action" -> Json.Str("pair"), "form" -> Json.obj(field -> Json.Str(link)))))
    assertEquals(memory.hidden.get("okay-remote.relay"), Some(pairing.text))
    assertEquals(memory.hidden.get("okay-remote.relay-origin"), Some("https://relay.example"))
    assertEquals(room.dialled, List(s"wss://relay.example/r/${pairing.room}/guest?auth=${pairing.auth}"))
    assert(d.open().contains("connecting"), d.open())

  test("not paired: the camera reads the pairing's QR code"):
    val room = Room()
    val memory = new Device.Memory(sockets = room.sockets, scanned = Some(link))
    val d = RemoteApp.over(memory)
    assert(d.open().contains("Scan the QR code"), d.open())
    d.act("""{"action": "scan-the-qr-code"}""")
    assertEquals(memory.hidden.get("okay-remote.relay"), Some(pairing.text))
    assertEquals(room.dialled.size, 1)

  test("a QR code that is not a pairing: said so, nothing kept"):
    val room = Room()
    val memory = new Device.Memory(sockets = room.sockets, scanned = Some("https://example.com/"))
    val d = RemoteApp.over(memory)
    assert(d.act("""{"action": "scan-the-qr-code"}""").contains("That is not a pairing link"))
    assert(memory.hidden.isEmpty)
