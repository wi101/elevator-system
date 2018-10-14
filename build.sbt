name := "elevator-system"

version := "0.1"

scalaVersion := "2.12.6"

libraryDependencies ++= Seq("org.scalaz" %% "scalaz-zio" % "0.2.6") ++ spec

lazy val spec = Seq(
  "org.specs2" %% "specs2-core" % "4.3.2" % Test,
  "org.specs2" %% "specs2-matcher-extra" % "4.3.4" % Test
)
