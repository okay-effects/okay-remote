package okay.remote.page

import okay.business.scala3.*
import okay.effects.json.Json

/** WHAT THE PAGE ASKS OF ITS DAEMON, as an effect: the daemon's events (in batches, none lost between screens), an act
 * sent — its answer one of the events, `answer` (`Live.answered`) — the pairing. How the daemon is reached — on the
 * machine, through the relay from a browser, through the relay from a phone — is the transport's */
enum Live[X]:
  case Listen() extends Live[Events[List[(String, Json)]]]
  /** an act sent, its id the program's: answered later, among the events */
  case Act(id: String, body: Json) extends Live[Unit]
  /** the page's address with its token: for another device on the same network */
  case Address() extends Live[String]
  /** the pairing given up: the secret forgotten here */
  case Unpair() extends Live[Unit]
  /** the pairing link this was paired by (to pair another app, the Telegram one): copied, never sent */
  case CopyPairing() extends Live[Boolean]
  /** a pairing link pasted (or scanned): kept, and the daemon reached by it */
  case Pair(link: String) extends Live[Boolean]
  /** a QR code read by the camera, where there is one (a phone): its text */
  case Scan() extends Live[Option[String]]

/** HOW THE DAEMON IS REACHED: its events into `put`, an act answered; what a platform keeps and copies */
trait Transport:
  def start(put: ((String, Json)) => Unit): Unit
  def act(body: Json)(done: Either[String, Json] => Unit): Unit
  def address: String = ""
  def forget(): Unit = ()
  def pairing: Option[String] = None
  /** a pairing link: kept, the daemon reached by it; whether it was one */
  def pair(link: String): Boolean = false
  /** a QR code read by the camera: none where there is no camera to ask (a browser's page pastes) */
  def scan(): Option[String] = None
  /** a text put where the person can paste it (the clipboard, the phone's sharing); whether it went */
  def copy(text: String): Boolean = false

object Live:
  /** an act's answer, as an event of the daemon's */
  def answered(id: String, r: Either[String, Json]): (String, Json) = "answer" -> Json.obj("id" -> Json.Str(id),
    "ok" -> Json.Bool(r.isRight), "body" -> r.fold(e => Json.obj("error" -> Json.Str(e)), identity))

  /** a pairing link's secret: `#s=` and 43 letters of base64url */
  def secretOf(link: String): Option[String] = "#s=([A-Za-z0-9_-]{43})".r.findFirstMatchIn(link).map(_.group(1))

  /** THE HANDLER over a transport; `later` runs a thunk on the platform's next turn (a browser's timer; on a phone at
   * once: its events come on the library's thread between steps) */
  final class Over(transport: Transport, later: (() => Unit) => Unit):
    private val inbox = Inbox(later)
    transport.start(inbox.put)

    def apply[R[_], Rest[_], A](p: A ! R)(using Split[Live, R, Rest]): A ! Rest =
      handle(p, Handler.General[Live, Rest, A, A](a => pure(a), [X] => (asked: Live[X], k: X => A ! Rest) =>
        asked match
          case Live.Listen() => k(inbox.events)
          case Live.Act(id, body) => transport.act(body)(r => inbox.put(answered(id, r))); k(())
          case Live.Address() => k(transport.address)
          case Live.Unpair() => transport.forget(); k(())
          case Live.CopyPairing() => k(transport.pairing.exists(transport.copy))
          case Live.Pair(link) => k(transport.pair(link))
          case Live.Scan() => k(transport.scan())))

  /** WHAT CAME, KEPT UNTIL A SCREEN LISTENS: all there is to the one listening, on the platform's next turn */
  final class Inbox(later: (() => Unit) => Unit):
    private var queue = Vector.empty[(String, Json)]
    private var listener: Option[List[(String, Json)] => Unit] = None
    private var scheduled = false
    def put(e: (String, Json)): Unit = { queue :+= e; soon() }
    private def soon(): Unit =
      if !scheduled && listener.nonEmpty && queue.nonEmpty then
        scheduled = true
        later { () =>
          scheduled = false
          listener.foreach { l =>
            if queue.nonEmpty then
              listener = None
              val batch = queue.toList
              queue = Vector.empty
              l(batch)
          }
        }
    val events: Events[List[(String, Json)]] = Events { k =>
      listener = Some(k)
      soon()
      () => if listener.exists(_ eq k) then listener = None
    }
