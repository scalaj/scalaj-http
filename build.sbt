name := "scalaj-http"

version := "2.3.0"

organization := "org.scalaj"

scalaVersion := "2.10.5"

libraryDependencies ++= Seq(
  "junit"                % "junit"              % "4.11"             % "test",
  "com.novocode"         % "junit-interface"    % "0.11"             % "test",
  "org.eclipse.jetty"    % "jetty-server"       % "8.1.19.v20160209" % "test"
)

libraryDependencies ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 9)) =>
      Seq("com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.3.3" % "test")
    case Some((2, 10 | 11)) =>
      Seq("com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.4.2" % "test")
    case _ =>
      Nil
  }
}

// TODO enable all tests when released jackson-module-scala_2.12
sources in Test := {
  val testSources = (sources in Test).value
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v <= 11 =>
      testSources
    case _ =>
      val excludeTests = Set("HttpBinTest.scala", "Json.scala")
      testSources.filterNot(f => excludeTests(f.getName))
  }
}

crossScalaVersions := Seq("2.9.3", "2.10.5", "2.11.7", "2.12.0-RC2")

javacOptions ++= Seq("-source", "1.6", "-target", "1.6")

scalacOptions in (Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value+"/root-doc.txt")

publishTo <<= version { v: String =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishMavenStyle := true

publishArtifact in Test := false

pomIncludeRepository := { x => false }

pomExtra := (
  <url>http://github.com/scalaj/scalaj-http</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <scm>
    <url>git@github.com:scalaj/scalaj-http.git</url>
    <connection>scm:git:git@github.com:scalaj/scalaj-http.git</connection>
  </scm>
  <developers>
    <developer>
      <id>hoffrocket</id>
      <name>Jon Hoffman</name>
      <url>http://github.com/hoffrocket</url>
    </developer>
  </developers>
)
