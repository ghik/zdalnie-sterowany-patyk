val avsCommonsVersion = "2.2.4"
val monixVersion = "3.3.0"
val scalaLoggingVersion = "3.9.3"
val logbackVersion = "1.2.3"
val scalatestVersion = "3.2.9"

inThisBuild(Seq(
  scalaVersion := "2.13.6",
  organization := "com.avsystem.patyk",

  Compile / scalacOptions ++= Seq(
    "-encoding", "utf-8",
    "-Yrangepos",
    "-explaintypes",
    "-feature",
    "-deprecation",
    "-unchecked",
    "-language:implicitConversions",
    "-language:existentials",
    "-language:dynamics",
    "-language:experimental.macros",
    "-language:higherKinds",
    "-Xfatal-warnings",
    "-Xlint:-missing-interpolator,-adapted-args,-unused,_",
    "-Ycache-plugin-class-loader:last-modified",
    "-Ycache-macro-class-loader:last-modified",
    "-Xnon-strict-patmat-analysis",
    "-Xlint:-strict-unsealed-patmat",
  ),
))

lazy val root = project.in(file("."))
  .aggregate(core, leftpad, ecdhe)

lazy val core = project.settings(
  libraryDependencies ++= Seq(
    "com.avsystem.commons" %% "commons-core" % avsCommonsVersion,
    "io.monix" %% "monix" % monixVersion,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "org.scalatest" %% "scalatest" % scalatestVersion
  )
)

lazy val leftpad = project
  .enablePlugins(JavaServerAppPackaging)
  .dependsOn(core)
  .settings(
    Compile / mainClass := Some("com.avsystem.patyk.LeftPadServer"),

    dockerRepository := Some("rjghik"),
    dockerUpdateLatest := true,
    dockerBaseImage := "openjdk:11",
    dockerExposedPorts := Seq(6969),
    dockerEnvVars := Map("PATYK_DATA_DIR" -> "/mnt/data"),
    dockerExposedVolumes := Seq("$PATYK_DATA_DIR")
  )

lazy val ecdhe = project
  .dependsOn(core)
