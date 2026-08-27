package com.polina.memify.routes

import com.polina.memify.db.PasswordResetTokens
import com.polina.memify.db.RefreshTokens
import com.polina.memify.db.Users
import com.polina.memify.generateAccessToken
import com.polina.memify.generateRefreshTokenValue
import com.polina.memify.hashPassword
import com.polina.memify.refreshTokenExpiryEpochMillis
import com.polina.memify.verifyGoogleIdToken
import com.polina.memify.verifyPassword
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

// Замена Firebase Auth: email/password + Google, свои access/refresh JWT-токены.
// Логика входа/регистрации, которая раньше была в AuthRepositoryImpl на клиенте
// поверх FirebaseAuth, теперь целиком здесь.

@Serializable
data class RegisterRequest(val email: String, val password: String, val username: String)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class GoogleAuthRequest(val idToken: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
    val email: String,
    val photoUrl: String?,
)

@Serializable
data class ErrorResponse(val error: String)

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Создаёт пару access+refresh токенов для пользователя и сохраняет хеш refresh-токена в БД. */
private fun issueTokens(userId: String): Pair<String, String> {
    val accessToken = generateAccessToken(userId)
    val refreshTokenValue = generateRefreshTokenValue()
    transaction {
        RefreshTokens.insert {
            it[RefreshTokens.userId] = userId
            it[tokenHash] = sha256Hex(refreshTokenValue)
            it[expiresAt] = Instant.ofEpochMilli(refreshTokenExpiryEpochMillis())
        }
    }
    return accessToken to refreshTokenValue
}

fun Route.authRoutes() {
    route("/auth") {
        post("/register") {
            val body = call.receive<RegisterRequest>()
            if (body.email.isBlank() || body.password.length < 6 || body.username.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Некорректные данные регистрации"))
            }

            val existing = transaction { Users.selectAll().where { Users.email eq body.email }.firstOrNull() }
            if (existing != null) {
                return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("Пользователь с таким email уже существует"))
            }

            val userId = UUID.randomUUID().toString()
            transaction {
                Users.insert {
                    it[id] = userId
                    it[username] = body.username
                    it[email] = body.email
                    it[passwordHash] = hashPassword(body.password)
                }
            }

            val (accessToken, refreshToken) = issueTokens(userId)
            call.respond(HttpStatusCode.Created, AuthResponse(accessToken, refreshToken, userId, body.username, body.email, null))
        }

        post("/login") {
            val body = call.receive<LoginRequest>()
            val row =
                transaction { Users.selectAll().where { Users.email eq body.email }.firstOrNull() }
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Неверный email или пароль"))

            val storedHash =
                row[Users.passwordHash]
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("У этого аккаунта вход только через Google"))

            if (!verifyPassword(body.password, storedHash)) {
                return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Неверный email или пароль"))
            }

            val (accessToken, refreshToken) = issueTokens(row[Users.id])
            call.respond(
                AuthResponse(accessToken, refreshToken, row[Users.id], row[Users.username], row[Users.email], row[Users.photoUrl]),
            )
        }

        post("/google") {
            val body = call.receive<GoogleAuthRequest>()
            val googleUser =
                try {
                    verifyGoogleIdToken(body.idToken)
                } catch (e: IllegalStateException) {
                    return@post call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Google auth misconfigured"))
                } ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Невалидный Google-токен"))

            val existing = transaction { Users.selectAll().where { Users.googleSub eq googleUser.sub }.firstOrNull() }

            val userId: String
            val username: String
            val photoUrl: String?
            if (existing != null) {
                userId = existing[Users.id]
                username = existing[Users.username]
                photoUrl = existing[Users.photoUrl]
            } else {
                userId = UUID.randomUUID().toString()
                username = googleUser.name ?: "Google User"
                photoUrl = googleUser.pictureUrl
                transaction {
                    Users.insert {
                        it[id] = userId
                        it[Users.username] = username
                        it[email] = googleUser.email
                        it[Users.photoUrl] = photoUrl
                        it[googleSub] = googleUser.sub
                    }
                }
            }

            val (accessToken, refreshToken) = issueTokens(userId)
            call.respond(AuthResponse(accessToken, refreshToken, userId, username, googleUser.email, photoUrl))
        }

        post("/refresh") {
            val body = call.receive<RefreshRequest>()
            val hash = sha256Hex(body.refreshToken)
            val row =
                transaction { RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.firstOrNull() }
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Невалидный refresh-токен"))

            val expired = row[RefreshTokens.expiresAt].isBefore(Instant.now())
            if (row[RefreshTokens.revoked] || expired) {
                return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Refresh-токен истёк или отозван"))
            }

            val newAccessToken = generateAccessToken(row[RefreshTokens.userId])
            call.respond(mapOf("accessToken" to newAccessToken))
        }

        post("/logout") {
            val body = call.receive<RefreshRequest>()
            val hash = sha256Hex(body.refreshToken)
            transaction {
                RefreshTokens.update({ RefreshTokens.tokenHash eq hash }) {
                    it[revoked] = true
                }
            }
            call.respond(HttpStatusCode.OK)
        }

        post("/forgot-password") {
            val body = call.receive<ForgotPasswordRequest>()
            val row = transaction { Users.selectAll().where { Users.email eq body.email }.firstOrNull() }

            // Намеренно не подтверждаем/не опровергаем существование email в ответе —
            // стандартная защита от перебора email-адресов.
            if (row != null) {
                val resetToken = generateRefreshTokenValue()
                transaction {
                    PasswordResetTokens.insert {
                        it[userId] = row[Users.id]
                        it[tokenHash] = sha256Hex(resetToken)
                        it[expiresAt] = Instant.now().plusSeconds(3600)
                    }
                }
                // TODO: тут нужно реально отправить письмо со ссылкой вида
                // https://.../reset-password?token=$resetToken через SMTP/SendGrid/Yandex Cloud Postbox —
                // почтовой инфраструктуры пока нет, см. пояснение в чате. Пока просто логируем токен.
                call.application.log.info("Password reset token for ${body.email}: $resetToken (STUB — email sending not configured)")
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Если аккаунт с таким email существует, на него отправлено письмо"))
        }

        post("/reset-password") {
            val body = call.receive<ResetPasswordRequest>()
            val hash = sha256Hex(body.token)
            val row =
                transaction { PasswordResetTokens.selectAll().where { PasswordResetTokens.tokenHash eq hash }.firstOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Невалидный токен"))

            if (row[PasswordResetTokens.used] || row[PasswordResetTokens.expiresAt].isBefore(Instant.now())) {
                return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Токен истёк или уже использован"))
            }

            transaction {
                Users.update({ Users.id eq row[PasswordResetTokens.userId] }) {
                    it[passwordHash] = hashPassword(body.newPassword)
                }
                PasswordResetTokens.update({ PasswordResetTokens.id eq row[PasswordResetTokens.id] }) {
                    it[used] = true
                }
            }

            call.respond(HttpStatusCode.OK)
        }
    }
}
