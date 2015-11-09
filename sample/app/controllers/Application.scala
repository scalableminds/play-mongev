package controllers

import play.api._
import play.api.Play.current
import play.api.mvc._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.concurrent.Future
import play.modules.reactivemongo._
import reactivemongo.api._
import play.modules.reactivemongo.json.collection.JSONCollection
import play.modules.reactivemongo.json._

case class Message(text: String)

object Message{
  implicit val meesageFormat = Json.format[Message]
}

object Application extends Controller {

  def collection: JSONCollection = ReactiveMongoPlugin.db.collection[JSONCollection]("test")

  def index = Action.async {
    collection.find(Json.obj()).one[Message].map{
      case Some(m) => 
        Ok(m.text)
      case _ =>
        InternalServerError("Failed :(")
    }
  }
}