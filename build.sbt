name := "scalaj-http"

version := "2.4.2"

organization := "org.scalaj"

scalaVersion := "2.12.8"

libraryDependencies ++= Seq(
  "junit"                         % "junit"                % "4.12"             % "test",
  "com.novocode"                  % "junit-interface"      % "0.11"             % "test",
  "org.eclipse.jetty"             % "jetty-server"         % "8.2.0.v20160908"  % "test",
  "org.eclipse.jetty"             % "jetty-servlet"        % "8.2.0.v20160908"  % "test",
  "org.eclipse.jetty"             % "jetty-servlets"       % "8.2.0.v20160908"  % "test"
)

libraryDependencies += {
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.13.0-SNAPSHOT"  % "test"
}

enablePlugins(BuildInfoPlugin)
buildInfoKeys := Seq[BuildInfoKey](version)
buildInfoPackage := "scalaj.http"

crossScalaVersions := Seq("2.11.12", "2.12.13", "2.13.6", "3.0.0")

javacOptions ++= Seq("-source", "1.7", "-target", "1.7")

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-Xfuture"
)

scalacOptions in (Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value+"/root-doc.txt")

resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

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
