import play.modules.reactivemongo._
import play.api._
import play.modules.reactivemongo.json.collection.JSONCollection
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object Global extends GlobalSettings{

  val collectionsToDrop = 
    "test" :: "play_evolutions" :: "play_evolutions_lock" :: Nil

  override def onStop(app: Application) {
    collectionsToDrop.foreach{ collection => 
      ReactiveMongoPlugin.db(app).collection[JSONCollection](collection).remove(Json.obj())
    }
  }
}