name := "scalaj-http"

version := "2.5.0"

organization := "org.scalaj"

scalaVersion := "2.12.15"

libraryDependencies ++= Seq(
  "junit"                         % "junit"                % "4.13.2"             % "test",
  "com.github.sbt"                % "junit-interface"      % "0.13.3"             % "test",
  "org.eclipse.jetty"             % "jetty-server"         % "8.2.0.v20160908"    % "test",
  "org.eclipse.jetty"             % "jetty-servlet"        % "8.2.0.v20160908"    % "test",
  "org.eclipse.jetty"             % "jetty-servlets"       % "8.2.0.v20160908"    % "test"
)

libraryDependencies += {
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.2"            % "test"
}

enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](version)
buildInfoPackage := "scalaj.http"

crossScalaVersions := Seq("2.12.15", "2.13.7", "3.1.0")

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-Xfuture"
)

scalacOptions in (Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value+"/root-doc.txt")

publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (version.value.trim.endsWith("SNAPSHOT"))
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
