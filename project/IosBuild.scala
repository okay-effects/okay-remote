import sbt._
import sbt.Keys._
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._
import scala.scalanative.build._

/**
 * AN APPLICATION'S LIBRARY FOR iOS (specs/ios.md): Scala Native linked into a static library, for the target
 * `OKAY_IOS_TARGET` names — `simulator` (arm64, the default), `device` (arm64) — against the SDK `xcrun` finds. Not
 * set at all (`OKAY_IOS_TARGET=host`), the library is the Mac's, for a test of the C functions. Linked for size: an
 * app's Scala is mostly screens, and `releaseSize` takes a tenth off the app (specs/mobile.md).
 */
object IosBuild {
  def sdk(name: String): String =
    scala.sys.process.Process(Seq("xcrun", "--sdk", name, "--show-sdk-path")).!!.trim

  val library: Seq[Def.Setting[_]] = Seq(nativeConfig ~= { c =>
    val base = c.withBuildTarget(BuildTarget.libraryStatic).withGC(GC.immix).withMode(Mode.releaseSize)
    sys.env.getOrElse("OKAY_IOS_TARGET", "simulator") match {
      case "host" => base
      case target =>
        val (triple, sdkName) =
          if (target == "device") ("arm64-apple-ios17.0", "iphoneos")
          else ("arm64-apple-ios17.0-simulator", "iphonesimulator")
        val root = sdk(sdkName)
        base.withTargetTriple(triple).withCompileOptions(c.compileOptions ++ Seq("-isysroot", root))
          .withLinkingOptions(c.linkingOptions ++ Seq("-isysroot", root))
    }
  })
}
