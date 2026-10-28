// okay-remote: your coding agents, on your machine, from your phone — Scala 3, effects, okay-guard's policy
import org.scalajs.linker.interface.ModuleKind
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val scala3 = "3.3.8"
val okayGuard = "0.1.0"
val okayUi = "0.1.0"
val okayServerless = "0.1.0"

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := scala3
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:imports,privates,locals")
ThisBuild / resolvers ++= Seq("okay-guard" at "https://okay-effects.github.io/okay-guard/maven",
  "okay-serverless" at "https://okay-effects.github.io/okay-serverless/maven",
  "okay-store" at "https://okay-effects.github.io/okay-store/maven",
  "okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
  "okay-ui" at "https://okay-effects.github.io/okay-ui/maven",
  "okay-proof" at "https://okay-effects.github.io/okay-proof/maven")

val munit = Def.setting("org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE REMOTE'S LOGIC: the agent's steps, the sessions, the policy over its calls — no platform */
lazy val core = crossProject(JVMPlatform, JSPlatform).crossType(CrossType.Pure).in(file("core"))
  .settings(name := "okay-remote-core",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-guard-core" % okayGuard,
      "io.github.okay-effects" %%% "okay-guard-policy" % okayGuard,
      "io.github.okay-effects" %%% "okay-guard-acp" % okayGuard, munit.value))

/** THE PAGE: an okay-ui program, phone first — the projects, a session as a conversation, the policy */
lazy val web = (project in file("web")).enablePlugins(ScalaJSPlugin).dependsOn(core.js)
  .settings(name := "okay-remote-web",
    Compile / scalaJSModuleInitializers :=
      Seq(org.scalajs.linker.interface.ModuleInitializer.mainMethodWithArgs("okay.remote.web.Main", "main")
        .withModuleID("page")),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-ui" % okayUi,
      "io.github.okay-effects" %%% "okay-serverless-relay" % okayServerless, munit.value))

/** THE DAEMON ON THE MACHINE: over okay-guard's `Os` on the JVM — the agent's processes, the page's server */
lazy val daemon = (project in file("daemon")).dependsOn(core.jvm)
  .settings(name := "okay-remote-daemon", Compile / mainClass := Some("okay.remote.daemon.Main"),
    libraryDependencies ++= Seq("io.github.okay-effects" %% "okay-guard-jvm" % okayGuard,
      "io.github.okay-effects" %% "okay-serverless-relay" % okayServerless, munit.value),
    Compile / resourceGenerators += Def.task {
      val w = (web / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
      val _ = (web / Compile / fullLinkJS).value
      val out = (Compile / resourceManaged).value / "okay-remote-page"
      IO.delete(out)
      val modules = (w * "*.js").get.filter(f => f.getName == "page.js" || f.getName.startsWith("internal-"))
      val site = (baseDirectory.value / ".." / "site" * "*").get
      (modules ++ site).map { f => IO.copyFile(f, out / f.getName); out / f.getName }
    }.taskValue)

/** the daemon as ONE jar: `java -jar dist/okay-remote.jar` */
val fatJar = taskKey[File]("dist/okay-remote.jar")
fatJar := {
  val cp = (daemon / Compile / fullClasspath).value.map(_.data)
  val tmp = target.value / "fat"
  IO.delete(tmp)
  cp.filter(_.isFile).foreach(j => IO.unzip(j, tmp, new SimpleFilter(n =>
    !n.startsWith("META-INF/") || !(n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA") ||
      n == "META-INF/MANIFEST.MF"))))
  cp.filter(_.isDirectory).foreach(d => IO.copyDirectory(d, tmp, overwrite = true))
  val manifest = new java.util.jar.Manifest
  manifest.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
  manifest.getMainAttributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, "okay.remote.daemon.Main")
  val out = baseDirectory.value / "dist" / "okay-remote.jar"
  IO.jar(Path.allSubpaths(tmp).toSeq, out, manifest, None)
  out
}

/** THE RELAY: a Cloudflare Worker — the page, and rooms joining a daemon and its phones (specs/remote.md) */
lazy val relay = (project in file("relay")).enablePlugins(ScalaJSPlugin)
  .settings(name := "okay-remote-relay", scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-serverless-cloudflare" % okayServerless,
      "io.github.okay-effects" %%% "okay-serverless-relay" % okayServerless,
      "io.github.okay-effects" %%% "okay-serverless-telegram" % okayServerless))

/** the relay, linked, with the page's files as text modules: `relay/dist` — then `npx wrangler deploy` */
val relayDist = taskKey[File]("the relay in relay/dist")
relayDist := {
  val r = (relay / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val w = (web / Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value
  val _ = ((relay / Compile / fullLinkJS).value, (web / Compile / fullLinkJS).value)
  val out = baseDirectory.value / "relay" / "dist"
  IO.delete(out)
  IO.copyFile(r / "main.js", out / "okay-remote-relay.js")
  val files = (w * "*.js").get.filter(f => f.getName == "page.js" || f.getName.startsWith("internal-")) ++
    (baseDirectory.value / "site" * "*").get
  files.foreach(f => IO.copyFile(f, out / "web" / (f.getName + ".txt")))
  val named = files.sortBy(_.getName).zipWithIndex
  IO.write(out / "assets.js", "// the page's files, by name (sbt relayDist)\n" +
    named.map { case (f, i) => s"""import f$i from "./web/${f.getName}.txt";""" }.mkString("\n") +
    "\nexport default {\n" + named.map { case (f, i) => s"""  "${f.getName}": f$i,""" }.mkString("\n") + "\n};\n")
  out
}
