package com.polina.memify.routes

import com.polina.memify.currentUserId
import com.polina.memify.db.PostLikes
import com.polina.memify.db.Posts
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
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
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// Замена Firestore-коллекции "posts" + Firebase Storage (PostsFbStorageDatasource на
// клиенте) и лайков, которые раньше были массивом userId прямо в документе поста
// (LikesRepositoryImpl). Тут лайки нормализованы через таблицу post_likes: наружу
// отдаём likesCount + isLiked, а не список id всех лайкнувших.

@Serializable
data class PostDto(
    val id: String,
    val authorId: String,
    val imageUrl: String,
    val templateId: String?,
    val width: Int,
    val height: Int,
    val likesCount: Int,
    val isLiked: Boolean,
)

@Serializable
data class CreatePostRequest(
    val imageUrl: String,
    val templateId: String? = null,
    val width: Int,
    val height: Int,
)

private fun likesCountFor(postId: String): Int =
    transaction { PostLikes.selectAll().where { PostLikes.postId eq postId }.count() }.toInt()

private fun isLikedBy(postId: String, userId: String?): Boolean =
    userId != null &&
        transaction {
            PostLikes.selectAll().where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }.count() > 0
        }

private fun buildPostDto(
    postId: String,
    authorId: String,
    imageUrl: String,
    templateId: String?,
    width: Int,
    height: Int,
    currentUserId: String?,
): PostDto =
    PostDto(
        id = postId,
        authorId = authorId,
        imageUrl = imageUrl,
        templateId = templateId,
        width = width,
        height = height,
        likesCount = likesCountFor(postId),
        isLiked = isLikedBy(postId, currentUserId),
    )

fun Route.postsRoutes() {
    route("/posts") {
        // Лента — публичная. isLiked посчитается, только если пришёл валидный токен.
        authenticate("jwt-auth", optional = true) {
            get {
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
                val currentUserId = call.currentUserId()

                val rows = transaction { Posts.selectAll().orderBy(Posts.createdAt to SortOrder.DESC).limit(limit).toList() }
                val result =
                    rows.map {
                        buildPostDto(
                            it[Posts.id], it[Posts.authorId], it[Posts.imageUrl],
                            it[Posts.templateId], it[Posts.width], it[Posts.height], currentUserId,
                        )
                    }
                call.respond(result)
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val currentUserId = call.currentUserId()
                val row =
                    transaction { Posts.selectAll().where { Posts.id eq id }.firstOrNull() }
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(
                    buildPostDto(
                        row[Posts.id], row[Posts.authorId], row[Posts.imageUrl],
                        row[Posts.templateId], row[Posts.width], row[Posts.height], currentUserId,
                    ),
                )
            }
        }

        authenticate("jwt-auth") {
            // Картинку заранее заливают через POST /upload и сюда передают уже готовый imageUrl.
            post {
                val userId = call.currentUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<CreatePostRequest>()

                val postId =
                    transaction {
                        val stmt =
                            Posts.insert {
                                it[authorId] = userId
                                it[imageUrl] = body.imageUrl
                                it[templateId] = body.templateId
                                it[width] = body.width
                                it[height] = body.height
                            }
                        stmt[Posts.id]
                    }

                call.respond(
                    HttpStatusCode.Created,
                    buildPostDto(postId, userId, body.imageUrl, body.templateId, body.width, body.height, userId),
                )
            }

            post("/{id}/toggle-like") {
                val postId = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId = call.currentUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val nowLiked =
                    transaction {
                        val alreadyLiked =
                            PostLikes.selectAll().where { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }.count() > 0

                        if (alreadyLiked) {
                            PostLikes.deleteWhere { (PostLikes.postId eq postId) and (PostLikes.userId eq userId) }
                            false
                        } else {
                            PostLikes.insert {
                                it[PostLikes.postId] = postId
                                it[PostLikes.userId] = userId
                            }
                            true
                        }
                    }

                call.respond(mapOf("isLiked" to nowLiked, "likesCount" to likesCountFor(postId)))
            }

            get("/liked") {
                val userId = call.currentUserId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val likedPostIds = transaction { PostLikes.selectAll().where { PostLikes.userId eq userId }.map { it[PostLikes.postId] } }
                val result =
                    transaction {
                        Posts.selectAll().where { Posts.id inList likedPostIds }.map {
                            buildPostDto(
                                it[Posts.id], it[Posts.authorId], it[Posts.imageUrl],
                                it[Posts.templateId], it[Posts.width], it[Posts.height], userId,
                            )
                        }
                    }
                call.respond(result)
            }
        }
    }
}
