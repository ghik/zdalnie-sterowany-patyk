scalaVersion := "2.13.5"
organization := "com.avsystem.patyk"
ideBasePackages := Seq("com.avsystem.patyk")

val avsCommonsVersion = "2.1.1"
val monixVersion = "3.3.0"
val scalaLoggingVersion = "3.9.3"
val logbackVersion = "1.2.3"

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
)

libraryDependencies ++= Seq(
  "com.avsystem.commons" %% "commons-core" % avsCommonsVersion,
  "io.monix" %% "monix" % monixVersion,
  "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion,
  "ch.qos.logback" % "logback-classic" % logbackVersion,
)
