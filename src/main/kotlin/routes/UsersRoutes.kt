package com.polina.memify.routes

import com.polina.memify.currentUserId
import com.polina.memify.db.Users
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

// Замена Firestore-коллекции "users" (UserRepositoryImpl на клиенте): профиль
// пользователя теперь читается/пишется здесь. Пароль тут не хранится и не
// обновляется — это отдельно, через /auth/* (см. AuthRoutes.kt).

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    val photoUrl: String?,
    val phone: String?,
    val tsi: Int,
)

@Serializable
data class UpdateProfileRequest(
    val username: String? = null,
    val photoUrl: String? = null,
    val phone: String? = null,
    val tsi: Int? = null,
)

private fun ResultRow.toUserDto(): UserDto =
    UserDto(
        id = this[Users.id],
        username = this[Users.username],
        email = this[Users.email],
        photoUrl = this[Users.photoUrl],
        phone = this[Users.phone],
        tsi = this[Users.tsi],
    )

fun Route.usersRoutes() {
    route("/users") {
        // Публичный профиль по id — используется, например, чтобы подставить автора поста в ленте.
        get("/{id}") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val row =
                transaction { Users.selectAll().where { Users.id eq id }.firstOrNull() }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(row.toUserDto())
        }

        authenticate("jwt-auth") {
            get("/me") {
                val userId = call.currentUserId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val row =
                    transaction { Users.selectAll().where { Users.id eq userId }.firstOrNull() }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(row.toUserDto())
            }

            patch("/me") {
                val userId = call.currentUserId() ?: return@patch call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<UpdateProfileRequest>()

                transaction {
                    Users.update({ Users.id eq userId }) { stmt ->
                        body.username?.let { stmt[username] = it }
                        body.photoUrl?.let { stmt[photoUrl] = it }
                        body.phone?.let { stmt[phone] = it }
                        body.tsi?.let { stmt[tsi] = it }
                    }
                }

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
