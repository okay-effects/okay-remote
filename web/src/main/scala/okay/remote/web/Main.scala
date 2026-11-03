package okay.remote.web

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.business.scala3.*
import okay.remote.page.{Live, Page, Transport}
import okay.scala3.std.Async
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import okay.serverless.relay.Pairing

/** THE PAGE, in one line: the program, over its daemon — on the machine (a token, `?t=`) or through the relay (a
 * pairing's secret, `#s=`, never sent anywhere) — each taken out of the address and kept in this browser */
object Main:
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    val store = dom.window.localStorage
    val token = Option(new dom.URLSearchParams(dom.window.location.search).get("t")).filter(_.nonEmpty)
    val secret = Option(dom.window.location.hash).filter(_.startsWith("#s=")).map(_.drop(3)).filter(_.nonEmpty)
    token.foreach(store.setItem(Web.TokenKey, _))
    secret.foreach(store.setItem(Web.RelayKey, _))
    if token.nonEmpty || secret.nonEmpty then dom.window.history.replaceState(null, "", dom.window.location.pathname)
    val transport: Transport =
      Option(store.getItem(Web.TokenKey)).filter(_.nonEmpty).map(OverHttp(_))
        .orElse(Option(store.getItem(Web.RelayKey)).flatMap(Pairing.of(_, "okay-remote")).map(OverRelay(_)))
        .getOrElse(Unpaired)
    val nav = dom.window.navigator.asInstanceOf[js.Dynamic]
    if !js.isUndefined(nav.serviceWorker) then nav.serviceWorker.register("sw.js")
    // the page's row: the screens, the daemon, and the browser's Async (what the page's screens wait on)
    type Row[X] = (Async + Ui + Live)[X]
    val live = Live.Over(transport, f => { val _ = js.timers.setTimeout(0)(f()) })
    val _ = Async.start(Dom.run(root, Look.skin, follow = true)(live(Page.main[Row])))


object Look:
  val skin: Skin =
    val calm = Skin.calm(accent = "#0f766e", deep = "#13232b")
    Skin(calm.look, calm.css + Css.marks)
