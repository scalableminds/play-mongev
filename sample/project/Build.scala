import sbt._
import Keys._
import play.Play.autoImport._
import PlayKeys._

object ApplicationBuild extends Build {
  val appName         = "play-mongev-sample"
  val appVersion      = "0.3-SNAPSHOT"
  
  // sub-project this depends on
  val module = RootProject(file("../module"))

  val dependencies = Seq(
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.5.0.akka23")

  val main = Project(appName, file(".")).enablePlugins(play.PlayScala).settings(
    version := appVersion, 
    libraryDependencies ++= dependencies,
    organization := "com.scalableminds",
    organizationName := "scalable minds UG (haftungsbeschränkt) & Co. KG",
    organizationHomepage := Some(new URL("http://scm.io"))
  ).dependsOn(module)
}