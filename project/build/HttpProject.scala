import sbt._
import sbt.Process._

class HttpProject(info: ProjectInfo) extends DefaultProject(info) {
  override def managedStyle = ManagedStyle.Maven
  val publishTo = "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
  override def packageSrcJar= defaultJarPath("-sources.jar")
  val sourceArtifact = Artifact.sources(artifactID)
  override def packageToPublishActions = super.packageToPublishActions ++ Seq(packageSrc)
  
  val specs = if (buildScalaVersion.startsWith("2.7.")) {
    "org.scala-tools.testing" % "specs" % "1.6.2.2" % "test" withSources()
  } else {
    "org.scala-tools.testing" %% "specs" % "1.6.5" % "test" withSources()
  }
}