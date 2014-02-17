/*
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
/*
 * Copyright (C) 2013-2014 scalable minds UG & Co. KG <http://www.scm.io>
 */

/**
 * This software is partly based on the playframework's play-jdbc project (https://github.com/playframework/playframework/blob/master/framework/src/play-jdbc)
 * and part of the code used here has its origin in the mentioned project.
 */

package com.scalableminds.mongev

import java.io._
import scalax.io.JavaConverters._
import play.api._
import play.api.libs._
import play.api.libs.Codecs._
import scala.util.control.NonFatal
import play.core.HandleWebCommandSupport
import play.api.libs.json._
import play.api.libs.Files.TemporaryFile
import org.slf4j.LoggerFactory

/**
 * An DB evolution - database changes associated with a software version.
 *
 * An evolution includes ‘up’ changes, to upgrade to to the version, as well as ‘down’ changes, to downgrade the database
 * to the previous version.
 *
 * @param revision revision number
 * @param db_up the DB statements for UP application
 * @param db_down the DB statements for DOWN application
 */
private[mongev] case class Evolution(revision: Int, db_up: String = "", db_down: String = "") {
  val hash = sha1(db_down.trim + db_up.trim)
}

private[mongev] object Evolution {
  implicit val evolutionReads = Json.reads[Evolution]
}

/**
 * A Script to run on the database.
 */
private[mongev] trait Script {

  /**
   * Original evolution.
   */
  val evolution: Evolution

  /**
   * The complete script to be run.
   */
  val script: String
}

/**
 * An UP Script to run on the database.
 *
 * @param evolution the original evolution
 * @param script the script to be run
 */
private[mongev] case class UpScript(evolution: Evolution, script: String) extends Script

/**
 * A DOWN Script to run on the database.
 *
 * @param evolution the original evolution
 * @param script the script to be run
 */
private[mongev] case class DownScript(evolution: Evolution, script: String) extends Script


private[mongev] trait MongevLogger {
  val logger = Logger("mongev")
}

private[mongev] trait EvolutionHelperScripts {

  def evolutionDBName = "play_evolutions"

  def lockDBName = "play_evolutions_lock"

  val allEvolutionsQuery = evolutionsQuery("")

  val unfinishedEvolutionsQuery = evolutionsQuery( """{"state" : {$in : ["applying_up", "applying_down"]}}""")

  def evolutionsQuery(query: String) =
    s"""
      |cursor = db.$evolutionDBName.find($query).sort( { "revision": -1 } );
      |print("[");
      |while ( cursor.hasNext() ) {
      |  printjson( cursor.next() );
      |  if(cursor.hasNext())
      |    print(",")
      |}
      |print("]");
    """.stripMargin

  def setAsApplied(revision: Int, state: String) =
    s"""
      |db.$evolutionDBName.update({"state" : "$state", "revision" : $revision}, {$$set: {"state" : "applied"}});
    """.stripMargin

  def setLastProblem(revision: Int, lastProblem: String) =
    s"""
      |db.$evolutionDBName.update({"revision" : $revision}, {$$set: {"last_problem" : "$lastProblem"}});
    """.stripMargin

  def updateState(revision: Int, updatedState: String) =
    s"""
      |db.$evolutionDBName.update({"revision" : $revision}, {$$set: {"state" : "$updatedState"}});
    """.stripMargin

  def removeAllInState(revision: Int, state: String) =
    s"""
      |db.$evolutionDBName.remove({"state": "$state", "revision" : $revision});
    """.stripMargin

  def remove(revision: Int) =
    s"""
      |db.$evolutionDBName.remove({"revision" : $revision});
    """.stripMargin

  def insert(js: JsObject) =
    s"""
      |db.$evolutionDBName.insert($js);
    """.stripMargin

  val acquireLock =
    s"""
      |result = db.runCommand({
      |  findAndModify: "$lockDBName",
      |  update: { $$inc: { lock: 1 } },
      |  upsert: true,
      |  new: true
      |});
      |printjson(result)
    """.stripMargin

  val releaseLock =
    s"""
      |result = db.runCommand({
      |  findAndModify: "$lockDBName",
      |  update: { $$inc: { lock: -1 } },
      |  new: true
      |});
      |printjson(result)
    """.stripMargin
}

private[mongev] trait MongoScriptExecutor extends MongevLogger {

  import scala.sys.process._

  def mongoCmd: String

  class StringListLogger(var messages: List[String] = Nil, var errors: List[String] = Nil) extends ProcessLogger {

    def out(s: => String) {
      messages ::= s
    }

    def err(s: => String) {
      errors ::= s
    }

    def buffer[T](f: => T): T = f
  }

  def execute(cmd: String): Option[JsValue] = {
    val input = TemporaryFile("mongo-script", ".js")

    Files.writeFile(input.file, cmd)
    val jsPath = input.file.getAbsolutePath

    val processLogger = new StringListLogger
    val result = Process(s"$mongoCmd --quiet $jsPath") ! (processLogger)

    val output = processLogger.messages.reverse.mkString("\n")

    result match {
      case 0 if output != "" =>
        val json = flattenObjectIds(output)
        try {
          Some(Json.parse(json))
        } catch {
          case e: com.fasterxml.jackson.core.JsonParseException =>
            logger.error("Failed to parse json: " + json)
            throw InvalidDatabaseEvolutionScript(json, result, "Failed to parse json result.")
        }
      case 0 =>
        None
      case errorCode =>
        throw InvalidDatabaseEvolutionScript(cmd, errorCode, processLogger.errors.reverse.mkString("\n"))
    }
  }

  def flattenObjectIds(js: String) = {
    val boidRx = "ObjectId\\(([\"a-zA-Z0-9]*)\\)" r

    boidRx.replaceAllIn(js, m => m.group(1))
  }
}

/**
 * Defines Evolutions utilities functions.
 */
trait Evolutions extends MongoScriptExecutor with EvolutionHelperScripts with MongevLogger {

  /**
   * Apply pending evolutions for the given DB.
   */
  def applyFor(path: java.io.File = new java.io.File(".")) {
    Play.current.plugin[MongevPlugin] map {
      plugin =>
        val script = evolutionScript(path, plugin.getClass.getClassLoader)
        applyScript(script)
    }
  }

  /**
   * Updates a local (file-based) evolution script.
   */
  def updateEvolutionScript(revision: Int = 1, comment: String = "Generated", ups: String, downs: String)(implicit application: Application) {
    import play.api.libs._

    val evolutions = application.getFile(evolutionsFilename(revision));
    Files.createDirectory(application.getFile(evolutionsDirectoryName));
    Files.writeFileIfChanged(evolutions,
      """|// --- %s
        |
        |// --- !Ups
        |%s
        |
        |// --- !Downs
        |%s
        |
        | """.stripMargin.format(comment, ups, downs));
  }

  /**
   * Resolves evolution conflicts.
   *
   * @param revision the revision to mark as resolved
   */
  def resolve(revision: Int) {
    execute(setAsApplied(revision, "applying_up"))
    execute(removeAllInState(revision, "applying_down"))
  }

  /**
   * Checks the evolutions state.
   *
   * @throws an error if the database is in an inconsistent state
   */
  def checkEvolutionsState() {
    execute(unfinishedEvolutionsQuery) map {
      case JsArray((problem: JsObject) +: _) =>
        val revision = (problem \ "revision").as[Int]
        val state = (problem \ "state").as[String]
        val hash = (problem \ "hash").as[String].take(7)
        val script = state match {
          case "applying_up" => (problem \ "db_up").as[String]
          case _ => (problem \ "db_down").as[String]
        }
        val error = (problem \ "last_problem").as[String]

        logger.error(error)

        val humanScript = "// --- Rev:" + revision + ", " + (if (state == "applying_up") "Ups" else "Downs") + " - " + hash + "\n\n" + script;

        throw InconsistentDatabase(humanScript, error, revision)
      case _ =>
      // everything is fine :)
    }
  }

  /**
   * Applies a script to the database.
   *
   * @param script the script to run
   */
  def applyScript(script: Seq[Script]) {
    def logBefore(s: Script) = s match {
      case UpScript(e, _) =>
        val json = Json.obj(
          "revision" -> e.revision,
          "hash" -> e.hash,
          "applied_at" -> System.currentTimeMillis(),
          "db_up" -> e.db_up,
          "db_down" -> e.db_down,
          "state" -> "applying_up",
          "last_problem" -> "")
        execute(insert(json))
      case DownScript(e, _) =>
        execute(updateState(e.revision, "applying_down"))
    }

    def logAfter(s: Script) = s match {
      case UpScript(e, _) =>
        execute(updateState(e.revision, "applied"))
      case DownScript(e, _) =>
        execute(remove(e.revision))
    }

    def updateLastProblem(message: String, revision: Int) =
      execute(setLastProblem(revision, message))

    checkEvolutionsState()

    var applying = -1

    try {
      script.foreach {
        s =>
          applying = s.evolution.revision
          logBefore(s)
          // Execute script
          execute(s.script)
          logAfter(s)
      }
    } catch {
      case NonFatal(e) =>
        updateLastProblem(e.getMessage, applying)
    }

    checkEvolutionsState()
  }

  /**
   * Translates an evolution script to something human-readable.
   *
   * @param script the script
   * @return a formatted script
   */
  def toHumanReadableScript(script: Seq[Script]): String = {
    val txt = script.map {
      case UpScript(ev, js) => "// --- Rev:" + ev.revision + ", Ups - " + ev.hash.take(7) + "\n" + js + "\n"
      case DownScript(ev, js) => "// --- Rev:" + ev.revision + ", Downs - " + ev.hash.take(7) + "\n" + js + "\n"
    }.mkString("\n")

    val hasDownWarning =
      "// !!! WARNING! This script contains DOWNS evolutions that are likely destructives\n\n"

    if (script.exists(_.isInstanceOf[DownScript])) hasDownWarning + txt else txt
  }

  /**
   * Computes the evolution script.
   *
   * @param path the application path
   * @param applicationClassloader the classloader used to load the driver
   * @return evolution scripts
   */
  def evolutionScript(path: File, applicationClassloader: ClassLoader): Seq[Product with Serializable with Script] = {
    val application = applicationEvolutions(path, applicationClassloader)

    Option(application).filterNot(_.isEmpty).map {
      case application =>
        val database = databaseEvolutions()

        val (nonConflictingDowns, dRest) = database.span(e => !application.headOption.exists(e.revision <= _.revision))
        val (nonConflictingUps, uRest) = application.span(e => !database.headOption.exists(_.revision >= e.revision))

        val (conflictingDowns, conflictingUps) = conflicts(dRest, uRest)

        val ups = (nonConflictingUps ++ conflictingUps).reverse.map(e => UpScript(e, e.db_up))
        val downs = (nonConflictingDowns ++ conflictingDowns).map(e => DownScript(e, e.db_down))

        downs ++ ups
    }.getOrElse(Nil)
  }

  /**
   *
   * Compares the two evolution sequences.
   *
   * @param downRest the seq of downs
   * @param upRest the seq of ups
   * @return the downs and ups to run to have the db synced to the current stage
   */
  def conflicts(downRest: Seq[Evolution], upRest: Seq[Evolution]) = downRest.zip(upRest).reverse.dropWhile {
    case (down, up) => down.hash == up.hash
  }.reverse.unzip

  /**
   * Reads evolutions from the database.
   */
  def databaseEvolutions(): Seq[Evolution] = {

    checkEvolutionsState()

    execute(allEvolutionsQuery).map {
      value: JsValue =>

        value.validate(Reads.list[Evolution]) match {
          case JsSuccess(v, _) => v
          case JsError(error) => throw new Exception(s"Couldn't parse elements of evolutions collection. Error: $error")
        }
    } getOrElse Nil
  }

  private val evolutionsDirectoryName = "conf/evolutions/"

  private def evolutionsFilename(revision: Int): String = evolutionsDirectoryName + revision + ".js"

  private def evolutionsResourceName(revision: Int): String = s"evolutions/$revision.js"

  /**
   * Reads the evolutions from the application.
   *
   * @param path the application path
   * @param applicationClassloader the classloader used to load the driver
   */
  def applicationEvolutions(path: File, applicationClassloader: ClassLoader): Seq[Evolution] = {

    val upsMarker = """^//.*!Ups.*$""".r
    val downsMarker = """^//.*!Downs.*$""".r

    val UPS = "UPS"
    val DOWNS = "DOWNS"
    val UNKNOWN = "UNKNOWN"

    val mapUpsAndDowns: PartialFunction[String, String] = {
      case upsMarker() => UPS
      case downsMarker() => DOWNS
      case _ => UNKNOWN
    }

    val isMarker: PartialFunction[String, Boolean] = {
      case upsMarker() => true
      case downsMarker() => true
      case _ => false
    }

    Collections.unfoldLeft(1) {
      revision =>
        Option(new File(path, evolutionsFilename(revision))).filter(_.exists).map(new FileInputStream(_)).orElse {
          Option(applicationClassloader.getResourceAsStream(evolutionsResourceName(revision)))
        }.map {
          stream =>
            (revision + 1, (revision, stream.asInput.string))
        }
    }.sortBy(_._1).map {
      case (revision, script) => {

        val parsed = Collections.unfoldLeft(("", script.split('\n').toList.map(_.trim))) {
          case (_, Nil) => None
          case (context, lines) => {
            val (some, next) = lines.span(l => !isMarker(l))
            Some((next.headOption.map(c => (mapUpsAndDowns(c), next.tail)).getOrElse("" -> Nil),
              context -> some.mkString("\n")))
          }
        }.reverse.drop(1).groupBy(i => i._1).mapValues {
          _.map(_._2).mkString("\n").trim
        }

        Evolution(
          revision,
          parsed.get(UPS).getOrElse(""),
          parsed.get(DOWNS).getOrElse(""))
      }
    }.reverse

  }

}

/**
 * Play Evolutions plugin.
 */
class MongevPlugin(app: Application) extends Plugin with HandleWebCommandSupport with MongevLogger with Evolutions {


  /**
   * The address of the mongodb server
   */
  lazy val mongoCmd = app.configuration.getString("mongodb.evolution.mongoCmd").getOrElse(
    throw new Exception("There is no mongodb.evolution.mongoCmd configuration available. " +
      "You need to declare informations about your mongo cmd in your configuration. E.g. \"mongo localhost:3232/myApp\""))

  /**
   * Is this plugin enabled.
   *
   * {{{
   * mongodb.evolution.enabled = true
   * }}}
   */
  override lazy val enabled = app.configuration.getBoolean("mongodb.evolution.enabled").getOrElse(false)

  lazy val applyDownEvolutions = app.configuration.getBoolean("mongodb.evolution.applyDownEvolutions").getOrElse(false)

  lazy val applyProdEvolutions = app.configuration.getBoolean("mongodb.evolution.applyProdEvolutions").getOrElse(false)

  /**
   * Checks the evolutions state.
   */
  override def onStart() {
    withLock {
      val script = evolutionScript(app.path, app.classloader)
      val hasDown = script.exists(_.isInstanceOf[DownScript])

      if (!script.isEmpty) {
        app.mode match {
          case Mode.Test => applyScript(script)
          case Mode.Dev => applyScript(script)
          case Mode.Prod if applyProdEvolutions && (applyDownEvolutions || !hasDown) => applyScript(script)
          case Mode.Prod if applyProdEvolutions && hasDown => {
            logger.warn("Your production database needs evolutions, including downs! \n\n" + toHumanReadableScript(script))
            logger.warn("Run with -Dmongodb.evolution.applyProdEvolutions=true and " +
              "-Dmongodb.evolution.applyDownEvolutions=true if you want to run them automatically, " +
              "including downs (be careful, especially if your down evolutions drop existing data)")

            throw InvalidDatabaseRevision(toHumanReadableScript(script))
          }
          case Mode.Prod => {
            logger.warn("Your production database needs evolutions! \n\n" + toHumanReadableScript(script))
            logger.warn("Run with -Dmongodb.evolution.applyProdEvolutions=true " +
              "if you want to run them automatically (be careful)")

            throw InvalidDatabaseRevision(toHumanReadableScript(script))
          }
          case _ => throw InvalidDatabaseRevision(toHumanReadableScript(script))
        }
      }
    }
  }

  def withLock(block: => Unit) {

    def unlock() = execute(releaseLock)

    if (app.configuration.getBoolean("mongodb.evolution.useLocks").getOrElse(false)) {
      execute(acquireLock) match {
        case Some(o: JsObject) =>
          val lock = (o \ "value" \ "lock").as[Int]
          if (lock == 1) {
            // everything is fine, we acquired the lock
            try {
              block
            } finally {
              unlock()
            }
          } else {
            // someone else holds the lock, we try again later
            logger.error(s"The db is already locked by another process." +
              " Wait for it to finish or delete the collection '$lockDBName'.")
            unlock()
          }
        case _ =>
          logger.error("Failed to acquire lock.")
      }
    } else
      block
  }

  def handleWebCommand(request: play.api.mvc.RequestHeader, sbtLink: play.core.SBTLink, path: java.io.File): Option[play.api.mvc.SimpleResult] = {

    val applyEvolutions = """/@evolutions/apply""".r
    val resolveEvolutions = """/@evolutions/resolve/([0-9]+)""".r

    lazy val redirectUrl = request.queryString.get("redirect").filterNot(_.isEmpty).map(_(0)).getOrElse("/")

    request.path match {

      case applyEvolutions() => {
        Some {
          val script = evolutionScript(app.path, app.classloader)
          applyScript(script)
          sbtLink.forceReload()
          play.api.mvc.Results.Redirect(redirectUrl)
        }
      }

      case resolveEvolutions(rev) => {
        Some {
          resolve(rev.toInt)
          sbtLink.forceReload()
          play.api.mvc.Results.Redirect(redirectUrl)
        }
      }

      case _ => None

    }

  }

}

/**
 * Can be used to run off-line evolutions, i.e. outside a running application.
 */
object OfflineEvolutions extends MongevLogger {

  def Evolutions(appPath: File) = new Evolutions {
    def mongoCmd = Configuration.load(appPath).getString("mongodb.evolution.mongoCmd").get
  }

  private def isTest: Boolean = Play.maybeApplication.exists(_.mode == Mode.Test)

  /**
   * Computes and applies an evolutions script.
   *
   * @param appPath the application path
   * @param classloader the classloader used to load the driver
   */
  def applyScript(appPath: File, classloader: ClassLoader) {
    val ev = Evolutions(appPath)
    val script = ev.evolutionScript(appPath, classloader)
    if (!isTest) {
      logger.warn("Applying evolution script for database:\n\n" + ev.toHumanReadableScript(script))
    }
    ev.applyScript(script)
  }

  /**
   * Resolve an inconsistent evolution..
   *
   * @param appPath the application path
   * @param revision the revision
   */
  def resolve(appPath: File, revision: Int) {
    val ev = Evolutions(appPath)
    if (!isTest) {
      logger.warn("Resolving evolution [" + revision + "] for database")
    }
    ev.resolve(revision)
  }

}

/**
 * Exception thrown when the database is not up to date.
 *
 * @param script the script to be run to resolve the conflict.
 */
case class InvalidDatabaseRevision(script: String) extends PlayException.RichDescription(
  "Database needs evolution!",
  "A MongoDB script need to be run on your database.") {

  def subTitle = "This MongoDB script must be run:"

  def content = script

  private val javascript = """
        document.location = '/@evolutions/apply?redirect=' + encodeURIComponent(location)
                           """.trim

  def htmlDescription = {

    <span>A MongoDB script will be run on your database -</span>
        <input name="evolution-button" type="button" value="Apply this script now!" onclick={javascript}/>

  }.mkString
}

/**
 * Exception thrown when the database is in inconsistent state.
 *
 * @param script the evolution script
 * @param error an inconsistent state error
 * @param rev the revision
 */
case class InconsistentDatabase(script: String, error: String, rev: Int) extends PlayException.RichDescription(
  "Database is in an inconsistent state!",
  "An evolution has not been applied properly. Please check the problem and resolve it manually before marking it as resolved.") {

  def subTitle = "We got the following error: " + error + ", while trying to run this MongoDB script:"

  def content = script

  private val javascript = """
        document.location = '/@evolutions/resolve/%s?redirect=' + encodeURIComponent(location)
                           """.format(rev).trim

  def htmlDescription: String = {

    <span>An evolution has not been applied properly. Please check the problem and resolve it manually before marking it as resolved -</span>
        <input name="evolution-button" type="button" value="Mark it resolved" onclick={javascript}/>

  }.mkString

}

/**
 * Exception thrown when the database evolution is invalid.
 *
 * @param script the script that was about to get run
 */
case class InvalidDatabaseEvolutionScript(script: String, exitCode: Int, error: String) extends PlayException.RichDescription(
  "Evolution failed!",
  s"Tried to run an evolution, but got the following return value: $exitCode") {

  def subTitle = "This MongoDB script produced an error while running on the db:"

  def content = script

  def htmlDescription = {

    <span>Error: "
      {error}
      ".</span>
      <span>Try to fix the issue!</span>

  }.mkString
}

