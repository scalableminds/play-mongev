import sbt._
import sbt.Keys._
import play.Play.autoImport._
import PlayKeys._
import com.typesafe.config._
import scala.Some
import xerial.sbt.Sonatype.SonatypeKeys._

object Publish {
  lazy val settings = Seq(
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    organization := "com.scalableminds",
    organizationName := "scalable minds UG (haftungsbeschränkt) & Co. KG",
    organizationHomepage := Some(url("http://scalableminds.com")),
    startYear := Some(2014),
    profileName := "com.scalableminds",
    description := "Play framework 2.x module to execute mongo DB evolutions via comand line",
    licenses := Seq("Apache 2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    homepage := Some(url("https://github.com/sclableminds/play-mongev")),
    scmInfo := Some(ScmInfo(url("https://github.com/sclableminds/play-mongev"), "https://github.com/scalableminds/play-mongev.git")),
    pomExtra := (
      <developers>
        <developer>
          <id>tmbo</id>
          <name>Tom Bocklisch</name>
          <email>tom.bocklisch@scalableminds.com</email>
          <url>http://github.com/tmbo</url>
        </developer>
      </developers>
    )
    )
}

object ApplicationBuild extends Build {

  val appVersion = "0.3.0"

  val mongev = Project("play-mongev", file("."))
    .enablePlugins(play.PlayScala)
    .settings((xerial.sbt.Sonatype.sonatypeSettings ++ Publish.settings):_*)
    .settings(
      scalaVersion := "2.11.2",
      version := appVersion
  )
}
