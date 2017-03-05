name := "scalaj-http"

version := "2.4.0"

organization := "org.scalaj"

scalaVersion := "2.11.8"

val jettyVersion = "9.4.2.v20170220"

libraryDependencies ++= Seq(
  "junit"                % "junit"              % "4.12"             % "test",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.8.7" % "test",
  "com.novocode"         % "junit-interface"    % "0.11"             % "test",
  "org.eclipse.jetty"    % "jetty-proxy"        % jettyVersion % "test",
  "org.eclipse.jetty"    % "jetty-server"       % jettyVersion % "test",
  "org.eclipse.jetty"    % "jetty-servlet"      % jettyVersion % "test"
)


crossScalaVersions := Seq("2.10.6", "2.11.8", "2.12.1")

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")

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
