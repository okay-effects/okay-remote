package okay.remote.phone

import okay.business.faces.{OnFaces, Skin}
import okay.business.scala3.*
import okay.effects.machine.Machine.self
import okay.faces.mobile.{Device, MobileApp, MobileDialogue, MobileFace}
import okay.remote.page.{Live, Page}

/** OKAY-REMOTE ON AN iPHONE, in one line (specs/ios.md): the page's program — the same one a browser runs — over the
 * relay through the phone's own socket and AES-GCM (`PhoneRelay`), its screens drawn by okay-faces' SwiftUI shell.
 * The shell opens it by its name, `okay.remote.phone.RemoteApp` */
class RemoteApp extends MobileApp:
  def dialogue: MobileDialogue = RemoteApp.over(device)

object RemoteApp:
  type Row[X] = (Ui + Live)[X]
  val look = Skin.calm(accent = "#0f766e", deep = "#13232b").look

  /** the program over a phone's port: a test's `Device.Memory`, the shell's on an iPhone. `later` at once: the
   * phone's events come on the library's one thread, between steps */
  def over(port: Device.Port): MobileDialogue =
    val live = Live.Over(PhoneRelay(port), f => f())
    MobileFace(OnFaces.program(steps(live(Page.main[Row]))), look)
