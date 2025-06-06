
package uk.gov.homeoffice.drt.testsystem

import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}
import uk.gov.homeoffice.drt.auth.Roles
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.db.tables.UserTableLike
import uk.gov.homeoffice.drt.routes.UserRoleProviderLike

object MockRoles {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(session: Session): Set[Role] = {

    val maybeRoles = session.data.get("mock-roles")
    val mockRoles = maybeRoles.map(_.split(",").toSet.flatMap(Roles.parse)).getOrElse(Set.empty)
    mockRoles
  }

  object MockRolesProtocol extends DefaultJsonProtocol {
    implicit val mockRoleConverters: RootJsonFormat[MockRoles] = jsonFormat1((v: JsValue) => {
      val roles = MockRoles(v.convertTo[Set[String]].flatMap(Roles.parse))
      roles
    })
  }
}

case class MockRoles(roles: Set[Role])

object TestUserRoleProvider extends UserRoleProviderLike {
  def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = {
    MockRoles(session) ++ userRolesFromHeader(headers)
  }

  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

  override val userService: UserTableLike = MockUserTable()
}
