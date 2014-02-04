import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {
  val appName         = "play-mongev-sample"
  val appVersion      = "0.2-SNAPSHOT"
  
  // sub-project this depends on
  val module = RootProject(file("../module"))

  val dependencies = Seq(
    "org.reactivemongo" %% "play2-reactivemongo" % "0.10.2")

  val main = play.Project(appName, appVersion, dependencies).settings(
    organization := "com.scalableminds",
    organizationName := "scalable minds UG (haftungsbeschränkt) & Co. KG",
    organizationHomepage := Some(new URL("http://scm.io"))
  ).dependsOn(module)
}