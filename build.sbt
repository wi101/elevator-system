name := "elevator-system"

version := "0.1"

scalaVersion := "2.12.6"

val ZIOVersion = "1.0.0-RC18-2"
libraryDependencies ++= Seq("dev.zio" %% "zio" % ZIOVersion) ++ spec

lazy val spec = Seq(
  "dev.zio" %% "zio-test" % ZIOVersion % Test,
  "dev.zio" %% "zio-test-sbt" % ZIOVersion % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
