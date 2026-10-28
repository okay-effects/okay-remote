package okay.remote.web

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.scala3.std.Lift
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

/** THE PAGE, in one line: the program, over its daemon — on the machine (a token, `?t=`) or through the relay (a
 * pairing's secret, `#s=`, never sent anywhere) — each taken out of the address and kept in this browser */
object Main:
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    val store = dom.window.localStorage
    val token = Option(new dom.URLSearchParams(dom.window.location.search).get("t")).filter(_.nonEmpty)
    val secret = Option(dom.window.location.hash).filter(_.startsWith("#s=")).map(_.drop(3)).filter(_.nonEmpty)
    token.foreach(store.setItem(Live.TokenKey, _))
    secret.foreach(store.setItem(Live.RelayKey, _))
    if token.nonEmpty || secret.nonEmpty then dom.window.history.replaceState(null, "", dom.window.location.pathname)
    val transport: Transport =
      Option(store.getItem(Live.TokenKey)).filter(_.nonEmpty).map(OverHttp(_))
        .orElse(Option(store.getItem(Live.RelayKey)).flatMap(decode).map(OverRelay(_)))
        .getOrElse(Unpaired)
    val nav = dom.window.navigator.asInstanceOf[js.Dynamic]
    if !js.isUndefined(nav.serviceWorker) then nav.serviceWorker.register("sw.js")
    val _ = Lift.lower(Dom.run(root, Look.skin, follow = true)(Live.Over(transport)(Page.main)))

  /** a pairing's secret, base64url: 32 bytes, or none */
  def decode(text: String): Option[Uint8Array] = scala.util.Try {
    val raw = js.Dynamic.global.atob(text.replace('-', '+').replace('_', '/') + "=" * ((4 - text.length % 4) % 4))
      .asInstanceOf[String]
    val b = new Uint8Array(raw.length)
    raw.indices.foreach(i => b(i) = raw.charAt(i).toShort)
    b
  }.toOption.filter(_.length == 32)

object Look:
  val skin: Skin =
    val calm = Skin.calm(accent = "#0f766e", deep = "#13232b")
    Skin(calm.look, calm.css + Css.marks)
