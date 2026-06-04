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
ThisBuild / version := "0.6.0"
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
      |  <!-- Activate jmockit's per-test scoping so MockUps are torn down after each test. Without
      |       it, a fault-injection mock (e.g. CompactionWithErrorsTest) leaks into a later test's
      |       compaction thread and fails it intermittently. -->
      |  <listeners><listener class-name="mockit.integration.testng.TestNGRunnerDecorator"/></listeners>
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

ThisBuild / sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost
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
  "org.slf4j" % "slf4j-api" % "1.7.36",
  "com.google.guava" % "guava" % "33.6.0-jre",
  "net.jpountz.lz4" % "lz4" % "1.3",
  "org.hamcrest" % "hamcrest-all" % "1.3" % Test,
  "org.apache.logging.log4j" % "log4j-core" % "2.26.0" % Test,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.26.0" % Test,
  "org.testng" % "testng" % "6.9.10" % Test,
  "org.jmockit" % "jmockit" % "1.49" % Test,
  "org.assertj" % "assertj-core" % "3.27.7" % Test
)

// Benchmark subproject: side-by-side comparison against RocksDB. Depends on HaloDB source
// directly (no publishLocal needed) and is NOT aggregated, so `sbt test` stays root-only.
// Run with: sbt "benchmarks/run quick"  (or large, plus --records/--reads/... overrides)
lazy val benchmarks = (project in file("benchmarks"))
  .dependsOn(LocalRootProject)
  .settings(
    name := "halodb-benchmarks",
    crossPaths := false,
    autoScalaLibrary := false,
    publish / skip := true,
    Compile / mainClass := Some("com.oath.halodb.benchmarks.Comparison"),
    // HaloDB's off-heap layer uses FFM restricted methods; RocksDB loads a native lib.
    fork := true,
    javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED", "-Xmx4g"),
    libraryDependencies ++= Seq(
      "org.rocksdb" % "rocksdbjni" % "10.10.1",
      "org.hdrhistogram" % "HdrHistogram" % "2.1.12",
      "org.slf4j" % "slf4j-simple" % "1.7.12"
    )
  )