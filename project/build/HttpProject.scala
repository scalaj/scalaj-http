import sbt._
import sbt.Process._

class HttpProject(info: ProjectInfo) extends DefaultProject(info) {
  override def managedStyle = ManagedStyle.Maven
  val publishTo = "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
  override def packageSrcJar= defaultJarPath("-sources.jar")
  val sourceArtifact = Artifact.sources(artifactID)
  override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageSrc)
  
  val commonsCodec = "commons-codec" % "commons-codec" % "1.4" % "compile" withSources()
  
  val specs = buildScalaVersion match {
    case v if v.startsWith("2.7.") => "org.scala-tools.testing" % "specs" % "1.6.2.2" % "test" withSources()
    case "2.8.0" => "org.scala-tools.testing" %% "specs" % "1.6.5" % "test" withSources()
    case "2.8.1" | "2.9.0-1" => "org.scala-tools.testing" %% "specs" % "1.6.8" % "test" withSources()
  }

  val dispatchOauth    = "net.databinder" %% "dispatch-oauth"     % "0.7.8" % "test"
}