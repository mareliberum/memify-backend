package com.polina.memify.routes

import com.polina.memify.currentUserId
import com.polina.memify.db.TemplateFavourites
import com.polina.memify.db.Templates
import com.polina.memify.jwtVerifier
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

@Serializable
data class TemplateDto(
    val id: String,
    val name: String,
    val url: String,
    val width: Int,
    val height: Int,
    val isFavourite: Boolean,
)

/**
 * Как [currentUserId], но не идёт через `authenticate(...)`, а сам достаёт и проверяет
 * Bearer-токен из заголовка. Любая проблема с ним (нет заголовка, просрочен, битая подпись)
 * трактуется как "гость" (null), а не как ошибка.
 *
 * Нужна для GET /templates: раньше там стоял `authenticate("jwt-auth", optional = true)`,
 * но у Ktor "optional" пропускает запрос без проверки только когда заголовка Authorization
 * нет вообще — если он есть, но токен невалиден/просрочен (обычное дело, access-токен живёт
 * 30 минут), Ktor всё равно отвечает 401, хотя список шаблонов должен быть публичным.
 * На клиенте это превращалось в "нужно войти" даже на вкладках Best/New.
 */
private fun ApplicationCall.optionalUserId(): String? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.removePrefix("Bearer ").trim()
    return try {
        jwtVerifier.verify(token).subject
    } catch (e: Exception) {
        null
    }
}

fun Route.templatesRoutes() {
    route("/templates") {
        // Список шаблонов — публичный, isFavourite посчитается, только если пришёл
        // валидный токен. ?sort=best|new, ?favourites=true.
        get {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
            val sort = call.request.queryParameters["sort"] ?: "best"
            val favouritesOnly = call.request.queryParameters["favourites"]?.toBoolean() ?: false
            val userId = call.optionalUserId()

            if (favouritesOnly && userId == null) {
                return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Нужно войти, чтобы смотреть избранное"))
            }

            val result =
                transaction {
                    val favouriteIds =
                        if (userId != null) {
                            TemplateFavourites.selectAll().where { TemplateFavourites.userId eq userId }
                                .map { it[TemplateFavourites.templateId] }
                                .toSet()
                        } else {
                            emptySet()
                        }

                    var query = Templates.selectAll()
                    if (favouritesOnly) {
                        query = query.andWhere { Templates.id inList favouriteIds }
                    }
                    query =
                        when (sort) {
                            "new" -> query.orderBy(Templates.createdAt to SortOrder.DESC)
                            else -> query.orderBy(Templates.usedCount to SortOrder.DESC)
                        }

                    query.limit(limit).map { row ->
                        TemplateDto(
                            id = row[Templates.id],
                            name = row[Templates.name],
                            url = row[Templates.url],
                            width = row[Templates.width],
                            height = row[Templates.height],
                            isFavourite = row[Templates.id] in favouriteIds,
                        )
                    }
                }
            call.respond(result)
        }

        // Лайк/дизлайк шаблона — требует свой JWT в заголовке Authorization: Bearer <token>.
        // Тут проверка обязательная (не optional), так что authenticate(...) остаётся как был.
        authenticate("jwt-auth") {
            post("/{id}/toggle-like") {
                val templateId = call.parameters["id"]
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId = call.currentUserId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val nowFavourite =
                    transaction {
                        val alreadyLiked =
                            TemplateFavourites
                                .selectAll()
                                .where { (TemplateFavourites.templateId eq templateId) and (TemplateFavourites.userId eq userId) }
                                .count() > 0

                        if (alreadyLiked) {
                            TemplateFavourites.deleteWhere {
                                (TemplateFavourites.templateId eq templateId) and (TemplateFavourites.userId eq userId)
                            }
                            false
                        } else {
                            // Внимание: сработает, только если строка с этим userId уже есть в таблице users
                            // (foreign key) — то есть пользователь должен быть один раз сохранён в базу при логине.
                            TemplateFavourites.insert {
                                it[TemplateFavourites.templateId] = templateId
                                it[TemplateFavourites.userId] = userId
                            }
                            true
                        }
                    }

                call.respond(mapOf("isFavourite" to nowFavourite))
            }
        }
    }
}
