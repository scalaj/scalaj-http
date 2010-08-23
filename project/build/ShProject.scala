import sbt._
import sbt.Process._

class ShProject(info: ProjectInfo) extends DefaultProject(info) {
  override def managedStyle = ManagedStyle.Maven
  val publishTo = "Scala Tools Nexus" at "http://nexus.scala-tools.org/content/repositories/releases/"
  Credentials(Path.userHome / ".ivy2" / ".credentials", log)
  override def extraTags = "scalaj" :: super.extraTags
  
  val commonsCodec = "commons-codec" % "commons-codec" % "1.3" % "compile" withSources()
  
  val specs = if (buildScalaVersion.startsWith("2.7.")) {
    "org.scala-tools.testing" % "specs" % "1.6.2.2" % "test" withSources()
  } else {
    "org.scala-tools.testing" %% "specs" % "1.6.5" % "test" withSources()
  }
}