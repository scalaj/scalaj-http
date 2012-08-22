name := "scalaj-http"

version := "0.3.2"

organization := "org.scalaj"

scalaVersion := "2.9.1"

libraryDependencies ++= Seq(
  "commons-codec"        % "commons-codec"      % "1.5"           % "compile" withSources(),
  "junit"                % "junit"              % "4.10"          % "test" withSources(),
  "com.novocode"         % "junit-interface"    % "0.8"           % "test"
)

crossScalaVersions := Seq("2.8.0", "2.8.1", "2.9.1", "2.9.2")

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