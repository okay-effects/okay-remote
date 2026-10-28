package okay.remote.daemon

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import java.util.concurrent.{CompletableFuture, CompletionStage}
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import okay.effects.json.Json
import okay.guard.jvm.JvmOs
import okay.remote.{Envelope, Pairing}

/** THE PAIRING'S SECRET and what it derives (specs/remote.md): the AES key, the room's auth, the room */
final class Keys(val secret: Array[Byte]):
  private def sha(label: String, b: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(label.getBytes(UTF_8) ++ b)
  private def hex(b: Array[Byte]) = b.map(x => f"${x & 0xff}%02x").mkString
  val enc: Array[Byte] = sha(Pairing.encLabel, secret)
  val auth: String = hex(sha(Pairing.authLabel, secret))
  val room: String = hex(sha("", auth.getBytes(UTF_8)))
  def text: String = Base64.getUrlEncoder.withoutPadding.encodeToString(secret)

  private val random = new SecureRandom
  private val key = new SecretKeySpec(enc, "AES")
  def seal(plain: String): String =
    val iv = new Array[Byte](12); random.nextBytes(iv)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv))
    c.updateAAD(Pairing.aad.getBytes(UTF_8))
    Base64.getUrlEncoder.withoutPadding.encodeToString(iv ++ c.doFinal(plain.getBytes(UTF_8)))
  def open(frame: String): Option[String] = scala.util.Try {
    val b = Base64.getUrlDecoder.decode(frame)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, b.take(12)))
    c.updateAAD(Pairing.aad.getBytes(UTF_8))
    new String(c.doFinal(b.drop(12)), UTF_8)
  }.toOption

object Keys:
  def fresh(): Keys = { val b = new Array[Byte](32); new SecureRandom().nextBytes(b); Keys(b) }
  def of(text: String): Option[Keys] =
    scala.util.Try(Base64.getUrlDecoder.decode(text)).toOption.filter(_.length == 32).map(Keys(_))

/**
 * THE DAEMON AT THE RELAY: it dials out (no port open here), keeps the line, dials again when it drops. Every frame
 * sealed and opened here (AES-GCM, the pairing's key); what the relay passes it cannot read. A frame not from a
 * phone, out of time or seen before is dropped (`Envelope`). Everything given to `got` on the daemon's loop.
 */
final class Relay(base: String, keys: Keys, got: (String, Json) => Unit, said: String => Unit):
  private val client = HttpClient.newHttpClient()
  private val seen = Envelope.Seen()
  private val random = new SecureRandom
  @volatile private var socket: Option[WebSocket] = None
  private var sending: CompletableFuture[WebSocket] = CompletableFuture.completedFuture(null)
  private var backoff = 1000
  private var up = false

  private def nonce: String =
    val b = new Array[Byte](12); random.nextBytes(b); b.map(x => f"${x & 0xff}%02x").mkString
  private val url = base.stripSuffix("/").replaceFirst("^http", "ws") + s"/r/${keys.room}/daemon?auth=${keys.auth}"

  def start(): Unit = dial()

  /** a frame to the phones: sealed, in order */
  def send(kind: String, fields: (String, Json)*): Unit = socket.foreach { ws =>
    val frame = keys.seal(Envelope.wrap("daemon", System.currentTimeMillis(), nonce, kind, fields*))
    synchronized { sending = sending.handle((_, _) => ()).thenCompose(_ => ws.sendText(frame, true)) }
  }

  private def dial(): Unit =
    client.newWebSocketBuilder().buildAsync(URI.create(url), listener).whenComplete { (ws, e) =>
      if e != null then JvmOs.loop.execute(() => dropped(s"not reached: ${Option(e.getCause).getOrElse(e).getMessage}"))
      else
        socket = Some(ws)
        JvmOs.loop.execute(() => { backoff = 1000; if !up then said("the relay is reached"); up = true })
        beat(ws)
    }

  /** a word now and then, so nothing on the way takes the line for idle (the relay answers it without waking) */
  private def beat(ws: WebSocket): Unit = JvmOs.later(30000)(if socket.exists(_ eq ws) then {
    synchronized { sending = sending.handle((_, _) => ()).thenCompose(_ => ws.sendText("ping", true)) }; beat(ws)
  })

  private def dropped(why: String): Unit =
    socket = None
    if up then said(s"the relay is lost ($why); dialling again")
    up = false
    JvmOs.later(backoff)(dial())
    backoff = math.min(backoff * 2, 30000)

  private val listener = new WebSocket.Listener:
    private val buf = new StringBuilder
    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      buf.append(data)
      if last then
        val frame = buf.toString; buf.clear()
        if frame != "pong" && frame.length < 3000000 then
          keys.open(frame).foreach(text => JvmOs.loop.execute(() =>
            Envelope.open(text, "phone", System.currentTimeMillis(), seen).foreach((kind, j) => got(kind, j))))
      ws.request(1)
      null
    override def onClose(ws: WebSocket, code: Int, reason: String): CompletionStage[?] =
      JvmOs.loop.execute(() => if socket.exists(_ eq ws) then dropped(s"closed $code $reason")); null
    override def onError(ws: WebSocket, e: Throwable): Unit =
      JvmOs.loop.execute(() => if socket.exists(_ eq ws) then dropped(Option(e.getMessage).getOrElse(e.toString)))
