import sbt._
import sbt.Keys._
import play.Project._
import com.typesafe.config._
import scala.Some

object Publish {
  object TargetRepository {
    def scmio: Project.Initialize[Option[sbt.Resolver]] = version { (version: String) =>
      val rootDir = "/srv/maven/"
      val path =
        if (version.trim.endsWith("SNAPSHOT"))
          rootDir + "snapshots/"
        else
          rootDir + "releases/"
      Some(Resolver.sftp("scm.io intern repo", "scm.io", 44144, path))
    }

    def sonatype: Def.Initialize[Option[sbt.Resolver]] = version { (version: String) =>
      val nexus = "https://oss.sonatype.org/"
      if (version.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    }  
  }

  lazy val settings = Seq(
    publishMavenStyle := true,
    publishTo <<= TargetRepository.scmio,
    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false },
    organization := "com.scalableminds",
    organizationName := "scalable minds UG (haftungsbeschränkt) & Co. KG",
    organizationHomepage := Some(url("http://scalableminds.com")),
    startYear := Some(2014),
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

  val version = "0.2.5"

  val name = "play-mongev"

  val mongev = play.Project(name, version, Seq(), settings = Publish.settings)
}
