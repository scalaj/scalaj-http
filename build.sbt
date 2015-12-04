name := "scalaj-http"

version := "2.1.0"

organization := "org.scalaj"

scalaVersion := "2.10.5"

libraryDependencies ++= Seq(
  "junit"                % "junit"              % "4.11"          % "test",
  "com.novocode"         % "junit-interface"    % "0.11"          % "test",
  "com.github.kristofa"  % "mock-http-server"   % "4.0"           % "test"
)

libraryDependencies += {
  val jacksonVersion = if (scalaVersion.value startsWith "2.9") "2.3.3" else "2.4.2"
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion % "test"
}

crossScalaVersions := Seq("2.9.3", "2.10.5", "2.11.7")

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
