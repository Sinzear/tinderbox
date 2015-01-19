package models.bot.tasks

import akka.actor._
import play.api.Logger
import utils.ImageUtil
import utils.face.FacialDetection
import scala.concurrent.ExecutionContext.Implicits._
import utils.tinder.model._
import utils.tinder.TinderApi
import services.FacialAnalysisService
import services.FacialAnalysisService.DEFAULT_FACE_SIZE
import services.SparkService


/**
 * This task performs a facial analysis by extracting faces and using k-means to analyze RGB values.
 */
class FacialAnalysisTask(val xAuthToken: String, val tinderBot: ActorRef, val userId: String, val matchUser: String, val swipeType: String) extends TaskActor {

  override def preStart() = {
    Logger.debug("[tinderbot] Starting new facial analysis task for %s." format matchUser)
    self ! "tick"
  }

  def storePlaceholderPixels {
    swipeType match {
      case "yes" =>
        FacialAnalysisService.appendYesPixels(userId, matchUser, Array[Double]())
        Logger.debug("[tinderbot] Stored YES placeholder pixels for user %s." format matchUser)

      case "no" =>
        FacialAnalysisService.appendNoPixels(userId, matchUser, Array[Double]())
        Logger.debug("[tinderbot] Stored NO placeholder pixels for user %s." format matchUser)
    }
  }

  def receive = {
    case "tick" =>
      new TinderApi(Some(xAuthToken)).getProfile(matchUser).map { result =>
        result match {
          case Left(error) =>
            storePlaceholderPixels
            Logger.error("[tinderbot] Couldn't retrieve profile for %s for reason %s." format (matchUser, error.toString))

          case Right(profile) =>
            var counts = 0
            profile.photos.map { photo =>
              val faces = FacialDetection(photo.url).extractFaces
              // only store data for photos with single faces to ensure it is the face of the user
              if(faces.size==1) {
                faces.foreach { face =>
                  // normal processing for eigenfaces
                  val pixels = ImageUtil.getNormalizedImagePixels(face, DEFAULT_FACE_SIZE, DEFAULT_FACE_SIZE)

                  swipeType match {
                    case "yes" =>
                      FacialAnalysisService.appendYesPixels(userId, matchUser, pixels)
                      Logger.debug("[tinderbot] Stored YES pixels for an image from user %s." format matchUser)

                    case "no" =>
                      FacialAnalysisService.appendNoPixels(userId, matchUser, pixels)
                      Logger.debug("[tinderbot] Stored NO pixels for an image from user %s." format matchUser)
                  }
                  counts += 1
                }
              }
            }

            // if no data was stored, leave a placeholder so it doesn't get re-processed
            if(counts==0) {
              storePlaceholderPixels
            }

            Logger.info("[tinderbot] Stored %s facial models for user %s." format (counts, matchUser))
        }
      }

      // make sure we properly shut down this actor
      self ! PoisonPill

    // someone is sending invalid messages
    case e: Any =>
      Logger.error("[tinderbot] Task received an unknown message")
      Logger.error("[tinderbot] Received: \n %s" format e.toString)

  }

}