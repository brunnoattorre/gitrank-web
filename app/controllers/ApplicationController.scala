package controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import forms.FeedbackForm
import models.daos.drivers.GitHubAPI
import models.forms.QuickstartForm
import models.services.{QuickstartService, RepositoryService, UserService}
import models.{Feedback, User}
import modules.CustomGitHubProvider
import org.apache.http.HttpStatus
import play.api.Play
import play.api.i18n.MessagesApi
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import play.api.Play.current


/**
 * The basic application controller.
 *
 * @param messagesApi The Play messages API.
 * @param env The Silhouette environment.
 * @param gitHubProvider The social provider registry.
 */
class ApplicationController @Inject()(
                                       val messagesApi: MessagesApi,
                                       val env: Environment[User, SessionAuthenticator],
                                       gitHubProvider: CustomGitHubProvider,
                                       repoService: RepositoryService,
                                       userService: UserService,
                                       gitHub: GitHubAPI,
                                       quickstartService: QuickstartService)
  extends Silhouette[User, SessionAuthenticator] {


  val STATUS_NOT_FOUND = 404
  /**
   * Handles the main action.
   *
   * @return The result to display.
   */
  def index = UserAwareAction.async { implicit request =>
    val repoCount = Play.configuration.getInt("gitrank.homeReposSuggestions").getOrElse(0)

    request.identity match {
      case Some(user) => userService.getOAuthInfo(user).flatMap({
        case Some(oauthInfo) => userService.getScoredRepositoriesNames(user).flatMap(filter =>
          gitHub.getUserStaredRepositories(repoCount, user, oauthInfo, filter).map(gitHubRepos =>
            Ok(views.html.home(gitHubProvider, request.identity, gitHubRepos))
          )
        )
        case None => throw new Error("User spotted without OAuth Credentials: " + user.username)
      })
      case None => gitHub.getMostStaredRepositories(repoCount).map(gitHubRepos =>
        Ok(views.html.home(gitHubProvider, request.identity, gitHubRepos))
      )
    }
  }

  /**
   * Handles the repository view
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system. (GitHub)
   * @return The html page of the repository
   */
  def gitHubRepository(owner: String, repositoryName: String, page: Option[Int] = None) = UserAwareAction.async { implicit request =>

    if (page.getOrElse(1) <= 0) {
      Future.successful(NotFound(views.html.error("notFound",STATUS_NOT_FOUND, "Not Found",
        "We cannot find the feedback page, unfortunately negative pages have not been invented!"))
      )
    } else {
      val repoName: String = owner + "/" + repositoryName
      repoService.getFromNeoOrGitHub(request.identity, repoName).flatMap({
        case Some(repository) => repoService.getFeedback(repoName, page).flatMap((feedback: Seq[Feedback]) =>
          repoService.getFeedbackPageCount(repoName).flatMap(totalPage => {

            if (totalPage == 0 || page.getOrElse(1) <= totalPage) {
              repoService.canAddFeedback(repoName, request.identity).flatMap({
                case true => repoService.canUpdateFeedback(repoName, request.identity).map(
                  canUpdate => Ok(views.html.repository(gitHubProvider, request.identity, repository, feedback, totalPage, true, canUpdate)
                    (owner, repositoryName, page.getOrElse(1))))
                case false => Future.successful(Ok(views.html.repository(gitHubProvider, request.identity, repository, feedback, totalPage)
                  (owner, repositoryName, page.getOrElse(1))))
              })
            } else {
              Future.successful(NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
                "The requested page does not exist")))
            }
          })
        )
        case None => Future.successful(NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
          "We cannot find the repository page, it is likely that you misspelled it, try something else!")))
      })
    }
  }

  /**
   * Handles the feedback page
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return the hml page with the scoring form for the given repository.
   */
  def giveFeedbackPage(owner: String, repositoryName: String) = UserAwareAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(request.identity, repoName).flatMap({
      case Some(repository) =>
        request.identity match {
          case Some(id) => repoService.canAddFeedback(repoName, request.identity).flatMap{
            case canAdd => canAdd match {
              case false => Future(Redirect(routes.ApplicationController.gitHubRepository(owner, repositoryName, None).url))
              case true => repoService.canUpdateFeedback(repoName, request.identity).flatMap(canUpdate =>
                repoService.getMapScoreFromUser(repoName,request.identity).map(map =>
                  map.isEmpty match {
                    case false => Ok(views.html.feedbackForm(gitHubProvider, request.identity)(owner, repositoryName, FeedbackForm.form.bind(map), canUpdate))
                    case true => Ok(views.html.feedbackForm(gitHubProvider, request.identity)(owner, repositoryName, FeedbackForm.form, canUpdate))
                  }
                ))
            }
          }
          case None => Future.successful(Ok(views.html.feedbackForm(gitHubProvider, request.identity)(owner, repositoryName, FeedbackForm.form, false)))
        }


      case None => Future(NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else!")))
    })
  }

  /**
   * Handles the feedback score post
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return Redirect to repo page
   */
  def postScore(owner: String, repositoryName: String, update: Option[Boolean]) = SecuredAction.async { implicit request =>
    FeedbackForm.form.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.feedbackForm(gitHubProvider, Some(request.identity))
        (owner, repositoryName, formWithErrors, update.getOrElse(false)))),
      data => repoService.giveScoreToRepo(owner,
        request.identity,
        repositoryName,
        data.scoreDocumentation,
        data.scoreMaturity,
        data.scoreDesign,
        data.scoreSupport,
        data.feedback
      ).map(repo => Redirect(routes.ApplicationController.gitHubRepository(owner, repositoryName, None).url))
    )
  }

  /**
   * Handles the quickstart guide post
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return Redirect to repo page
   */
  def postQuickstartGuide(owner: String, repositoryName: String) = SecuredAction.async { implicit request =>
    QuickstartForm.form.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.quickstartGuide(gitHubProvider, Some(request.identity))
        (owner, repositoryName, formWithErrors)))
      ,
      data => quickstartService.createQuickstart(
        request.identity,
        owner + "/" + repositoryName,
        data.title,
        data.description,
        QuickstartForm.validateUrl(data.url)
      ).map(q => Redirect(routes.ApplicationController.gitHubRepository(owner, repositoryName, None).url))
    )
  }

  /**
   * Handles the feedback page
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @return the hml page with the scoring form for the given repository.
   */
  def createGuidePage(owner: String, repositoryName: String) = UserAwareAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(request.identity, repoName).map({
      case Some(repository) =>
        Ok(views.html.quickstartGuide(gitHubProvider, request.identity)(owner, repositoryName, QuickstartForm.form))
      case None => NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else!"))
    })
  }

  /**
   * Service for upvoting a guide
   *
   * @param owner Owner of the repository on the repo system (GitHub)
   * @param repositoryName repository name on the repo system (GitHub)
   * @param id id of the guide
   * @param voteType if the vote is upvote or downvote
   * @return the guide
   */
  def upVote(owner: String, repositoryName: String, id: Int, voteType: String) = SecuredAction.async { implicit request =>
    val repoName: String = owner + "/" + repositoryName
    repoService.getFromNeoOrGitHub(Some(request.identity), repoName).flatMap({
      case Some(repository) =>
        voteType match {
          case "upvote" => quickstartService.updateVote(repository, true, id, request.identity)
            .map({
            case Some(guide) => Ok(Json.toJson(guide))
            case None => NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
              "We cannot find the guide, it is likely that you misspelled it, try something else!"))
          })
          case _ => quickstartService.updateVote(repository, false, id, request.identity)
            .map({
            case Some(guide) => Ok(Json.toJson(guide))
            case None =>
              NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
                "We cannot find the guide, it is likely that you misspelled it, try something else!"))
          })
        }
      case None => Future(NotFound(views.html.error("notFound", STATUS_NOT_FOUND, "Not Found",
        "We cannot find the repository feedback page, it is likely that you misspelled it, try something else!")))
    })
  }
}
