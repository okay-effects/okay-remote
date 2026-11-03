package okay.remote.phone

import okay.effects.json.Json
import okay.faces.mobile.Device
import okay.remote.J
import okay.remote.page.{Live, Transport}
import okay.serverless.relay.{Envelope, Hashes, Pairing}

/**
 * THROUGH THE RELAY, FROM A PHONE (specs/ios.md): okay-serverless' sealed room as a guest, over the phone's own
 * powers (okay-faces' `Device`, its port) — a socket to the room, every frame sealed and opened by the phone's AES-GCM,
 * the pairing's secret in the keychain. A frame not from the host, out of time or seen before, dropped (`Envelope`),
 * as the page's `Guest` does. The room's origin is the pairing link's: the relay the daemon paired with.
 */
final class PhoneRelay(port: Device.Port, label: String = "okay-remote") extends Transport:
  private val secretKey = "okay-remote.relay"
  private val originKey = "okay-remote.relay-origin"
  private var put: ((String, Json)) => Unit = _ => ()
  private var socket: Option[String] = None
  private var online = false
  // dialled again after a close, once until the room is reached: a room that is not there stays closed until the person
  // acts
  private var redialled = false
  private var n = 0
  private val asked = scala.collection.mutable.Map.empty[String, Either[String, Json] => Unit]
  private val seen = Envelope.Seen()
  private val random = new scala.util.Random()

  private def call[X](op: Device[X]): X = Device.answer(op, port.call(Device.request(op)))
  private def now: Long = call(Device.Now[Long](t => t))
  private def secret: Option[String] = call(Device.Secret[Option[String]](secretKey, s => s))
  private def origin: String = call(Device.Secret[Option[String]](originKey, s => s))
    .getOrElse("https://okay-remote.okay-effects.workers.dev")
  private def paired: Option[Pairing] = secret.flatMap(Pairing.of(_, label))

  port.events.listen(got)

  def start(put: ((String, Json)) => Unit): Unit =
    this.put = put
    put("scanner" -> Json.Bool(true))
    dial()

  /** the room dialled, if paired; not paired: the page says how to pair */
  private def dial(): Unit = paired match
    case None => put("unpaired" -> Json.Bool(true))
    case Some(p) =>
      val url = origin.replaceFirst("^http", "ws").stripSuffix("/") + s"/r/${p.room}/guest?auth=${p.auth}"
      call(Device.Socket[Either[String, String]](url, r => r)) match
        case Right(id) => socket = Some(id)
        case Left(why) => put("live" -> Json.Bool(false)); socket = None

  /** what the phone tells: the room's plain words, a sealed frame, the socket's end */
  private def got(e: Json): Unit =
    val id = J.str(e, "id")
    if id.nonEmpty && id == socket then J.str(e, "event") match
      case Some("socket") => J.str(e, "text").foreach {
          case "online" => present(true)
          case "offline" => present(false)
          case "pong" => ()
          case frame => paired.foreach(p => opened(p, frame))
        }
      case Some("closed") =>
        socket = None
        present(false)
        if !redialled then { redialled = true; dial() }
      case _ => ()

  private def present(up: Boolean): Unit =
    online = up
    if up then redialled = false
    put("live" -> Json.Bool(up))
    if up then send("hello")
    else { asked.values.foreach(_(Left("the daemon is not reached: try again"))); asked.clear() }

  private def opened(p: Pairing, frame: String): Unit =
    call(Device.Unseal[Option[String]](Hashes.base64url(p.enc), p.aad, frame, o => o)).foreach(text =>
      Envelope.open(text, "host", now, seen).foreach { (kind, j) =>
        kind match
          case "event" => put(J.str(j, "name").getOrElse("") -> J.get(j, "data").getOrElse(Json.Null))
          case "answer" => J.get(j, "id").map(x => Json.print(x)).flatMap(asked.remove).foreach(done =>
              if J.bool(j, "ok") then done(Right(J.get(j, "body").getOrElse(Json.Obj.empty)))
              else done(Left(J.get(j, "body").flatMap(J.str(_, "error")).getOrElse("refused"))))
          case _ => ()
      })

  /** a sealed frame to the host */
  private def send(kind: String, fields: (String, Json)*): Unit = (paired, socket) match
    case (Some(p), Some(id)) =>
      val nonce = Hashes.hex(Array.fill[Byte](12)(random.nextInt(256).toByte))
      val text = Envelope.wrap("guest", now, nonce, kind, fields*)
      val frame = call(Device.Seal[String](Hashes.base64url(p.enc), p.aad, text, f => f))
      if frame.nonEmpty then call(Device.SocketSend[Boolean](id, frame, b => b))
    case _ => ()

  def act(body: Json)(done: Either[String, Json] => Unit): Unit =
    if socket.isEmpty || !online then
      if socket.isEmpty then dial()
      done(Left("the relay is not reached: try again"))
    else
      n += 1
      val id = Json.Str(s"a$n")
      asked(Json.print(id)) = done
      send("act", "id" -> id, "body" -> body)

  override def pairing: Option[String] = secret.map(s => s"$origin/#s=$s")

  /** a pairing link (a scanned QR code, a pasted link): its secret and its relay kept in the keychain, the room
   * dialled */
  override def pair(link: String): Boolean = Live.secretOf(link).filter(Pairing.of(_, label).nonEmpty) match
    case None => false
    case Some(s) =>
      val at = link.takeWhile(_ != '#').stripSuffix("/")
      call(Device.Hide[Unit](secretKey, Some(s), u => u))
      if at.startsWith("http") then call(Device.Hide[Unit](originKey, Some(at), u => u))
      hangUp()
      redialled = false
      dial()
      true

  override def forget(): Unit =
    call(Device.Hide[Unit](secretKey, None, u => u))
    hangUp()
    put("unpaired" -> Json.Bool(true))

  /** the socket closed by this side: forgotten first, so its closing is not taken for the room's */
  private def hangUp(): Unit =
    val was = socket
    socket = None
    online = false
    was.foreach(id => call(Device.SocketClose[Unit](id, u => u)))

  override def copy(text: String): Boolean = call(Device.Share[Boolean](text, None, b => b))

  /** a QR code read by the camera: its text */
  override def scan(): Option[String] = call(Device.Scan[Option[String]](s => s))
