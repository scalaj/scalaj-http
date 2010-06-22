import sbt._
import sbt.Process._

class ShProject(info: ProjectInfo) extends DefaultProject(info) {
  
  val commonsCodec = "commons-codec" % "commons-codec" % "1.3" % "compile" withSources()
  val specs  = "org.scala-tools.testing" % "specs" % "1.6.2.1"  % "test" withSources()

  val dispatchOauth    = "net.databinder" %% "dispatch-oauth"     % "0.6.4" %"test"
}