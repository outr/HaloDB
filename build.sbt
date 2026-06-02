import java.io.File

// Variables
val org: String = "com.outr"
val projectName: String = "halodb-revive"
val githubOrg: String = "outr"
val email: String = "matt@matthicks.com"
val developerId: String = "darkfrog"
val developerName: String = "Matt Hicks"
val developerURL: String = "https://matthicks.com"

name := projectName

// Pure Java
crossPaths := false
autoScalaLibrary := false

ThisBuild / organization := org
ThisBuild / version := "0.5.7"
ThisBuild / scalacOptions ++= Seq("-unchecked", "-deprecation")

// HaloDB uses the Foreign Function & Memory API (java.lang.foreign), finalized in JDK 22.
ThisBuild / javacOptions ++= Seq("--release", "22")

// The tests use TestNG, which sbt has no built-in runner for, and jmockit, which needs a
// -javaagent. MemorySegment.reinterpret and the native malloc/free downcalls are restricted
// methods, so the forked JVM also needs --enable-native-access. This task wires all of that up
// so `sbt test` runs the full suite.
val testNG = taskKey[Unit]("Run the TestNG suite in a forked JVM (jmockit agent + FFM native access).")

testNG := {
  val log = streams.value.log
  val cp = (Test / fullClasspath).value.map(_.data)
  val jmockit = cp.find(_.getName.startsWith("jmockit"))
    .getOrElse(sys.error("jmockit jar not found on the test classpath"))
  val outDir = (Test / target).value / "testng"
  val suite = outDir / "testng.xml"
  IO.write(suite,
    """<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd">
      |<suite name="HaloDB" verbose="1">
      |  <test name="all"><packages><package name="com.oath.halodb"/></packages></test>
      |</suite>""".stripMargin)
  val options = ForkOptions().withRunJVMOptions(Vector(
    s"-javaagent:${jmockit.getAbsolutePath}",
    "--enable-native-access=ALL-UNNAMED"))
  val args = Seq(
    "-cp", cp.map(_.getAbsolutePath).mkString(File.pathSeparator),
    "org.testng.TestNG", suite.getAbsolutePath,
    "-d", (outDir / "out").getAbsolutePath)
  log.info(s"Running TestNG suite (${cp.size} classpath entries)...")
  val code = Fork.java(options, args)
  if (code != 0) sys.error(s"TestNG suite failed (exit code $code)")
}

Test / test := testNG.value

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / sonatypeProfileName := org
ThisBuild / licenses := Seq("MIT" -> url(s"https://github.com/$githubOrg/$projectName/blob/master/LICENSE"))
ThisBuild / sonatypeProjectHosting := Some(xerial.sbt.Sonatype.GitHubHosting(githubOrg, projectName, email))
ThisBuild / homepage := Some(url(s"https://github.com/$githubOrg/$projectName"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url(s"https://github.com/$githubOrg/$projectName"),
    s"scm:git@github.com:$githubOrg/$projectName.git"
  )
)
ThisBuild / developers := List(
  Developer(id=developerId, name=developerName, email=email, url=url(developerURL))
)

ThisBuild / resolvers += Resolver.mavenLocal

ThisBuild / outputStrategy := Some(StdoutOutput)

libraryDependencies ++= Seq(
  "org.slf4j" % "slf4j-api" % "1.7.12",
  "com.google.guava" % "guava" % "18.0",
  "net.jpountz.lz4" % "lz4" % "1.3",
  "org.hamcrest" % "hamcrest-all" % "1.3" % Test,
  "org.apache.logging.log4j" % "log4j-core" % "2.3" % Test,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.3" % Test,
  "org.testng" % "testng" % "6.9.13.6" % Test,
  "org.jmockit" % "jmockit" % "1.49" % Test,
  "org.assertj" % "assertj-core" % "3.8.0" % Test
)