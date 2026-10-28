package okay.remote.web

import okay.business.dom.Dom
import okay.business.faces.Skin
import okay.scala3.std.Lift
import org.scalajs.dom
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

/** THE PAGE, in one line: the program, over the daemon (its token kept in this browser), on okay-faces' DOM */
object Main:
  def main(args: Array[String]): Unit =
    val root = Option(dom.document.getElementById("app")).getOrElse(dom.document.body)
    val Key = "okay-remote.token"
    val came = Option(new dom.URLSearchParams(dom.window.location.search).get("t")).filter(_.nonEmpty)
    came.foreach { t =>
      dom.window.localStorage.setItem(Key, t)
      dom.window.history.replaceState(null, "", dom.window.location.pathname)
    }
    val token = came.orElse(Option(dom.window.localStorage.getItem(Key))).getOrElse("")
    if dom.window.navigator.asInstanceOf[scala.scalajs.js.Dynamic].serviceWorker != scala.scalajs.js.undefined then
      dom.window.navigator.asInstanceOf[scala.scalajs.js.Dynamic].serviceWorker.register("sw.js")
    val _ = Lift.lower(Dom.run(root, Look.skin, follow = true)(Live.Over(token)(Page.main)))

object Look:
  val skin: Skin =
    val calm = Skin.calm(accent = "#0f766e", deep = "#13232b")
    Skin(calm.look, calm.css + Css.marks)
