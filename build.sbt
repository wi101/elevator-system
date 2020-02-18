name := "elevator-system"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq("dev.zio" %% "zio" % "1.0.0-RC17") ++ spec

lazy val spec = Seq(
  "org.specs2" %% "specs2-core" % "4.8.3" % Test,
  "org.specs2" %% "specs2-matcher-extra" % "4.8.3" % Test
)
