package actors

import javax.inject.Inject

import akka.actor._
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import models.User
import models.daos.drivers.GitHubAPI
import models.services.UserService

import scala.concurrent.ExecutionContext.Implicits.global

object GitHubActor {
  def props = Props[GitHubActor]

  case class UpdateContributions(user: User, oAuth2Info: OAuth2Info)
}

class GitHubActor @Inject() (userService: UserService, gitHubAPI: GitHubAPI) extends Actor {
  import GitHubActor._

  def receive = {
    case UpdateContributions(user: User, oAuth2Info: OAuth2Info) => {

      println("-> Actor GitHub updating contributions")
      println("1. Getting the user contributed repositories")
      gitHubAPI.getContributedGitHubRepositories(user, oAuth2Info).map({
        case None => println("No repo found")
        case Some(repos) => println("found "+ repos.toString())
      })
    }
  }
}
