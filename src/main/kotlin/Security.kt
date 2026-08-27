package com.polina.memify

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import java.util.Date
import java.util.UUID

// Полностью своя авторизация вместо Firebase Auth:
//  - пароли хешируем bcrypt'ом (пароль в БД никогда не хранится в открытом виде);
//  - сессии — свои access/refresh JWT-токены (ktor-server-auth-jwt + com.auth0:java-jwt);
//  - Google-вход проверяем напрямую через Google (GoogleIdTokenVerifier), без Firebase
//    посередине — клиент присылает нам "сырой" Google ID-токен, полученный через
//    обычный GoogleSignIn SDK на Android.

private const val ACCESS_TOKEN_TTL_MINUTES = 30L
private const val REFRESH_TOKEN_TTL_DAYS = 30L
const val JWT_AUTH_PROVIDER_NAME = "jwt-auth"

private const val JWT_ISSUER = "memify-backend"
private const val JWT_AUDIENCE = "memify-app"

private val jwtSecret: String =
    System.getenv("JWT_SECRET") ?: run {
        println(
            "ВНИМАНИЕ: переменная окружения JWT_SECRET не задана — используется небезопасный дефолт, " +
                "годный только для локальной разработки. На реальном сервере обязательно задай свой " +
                "случайный секрет через JWT_SECRET (например, `openssl rand -hex 32`).",
        )
        "dev-insecure-secret-change-me"
    }

private val algorithm: Algorithm = Algorithm.HMAC256(jwtSecret)

val jwtVerifier: JWTVerifier =
    JWT.require(algorithm)
        .withIssuer(JWT_ISSUER)
        .withAudience(JWT_AUDIENCE)
        .build()

/** Access-токен, который клиент шлёт в `Authorization: Bearer <...>` на каждый запрос. */
fun generateAccessToken(userId: String): String =
    JWT.create()
        .withIssuer(JWT_ISSUER)
        .withAudience(JWT_AUDIENCE)
        .withSubject(userId)
        .withExpiresAt(Date(System.currentTimeMillis() + ACCESS_TOKEN_TTL_MINUTES * 60_000))
        .sign(algorithm)

/** Непрозрачное значение refresh-токена, которое отдаём клиенту. В БД хранится только его хеш. */
fun generateRefreshTokenValue(): String = UUID.randomUUID().toString() + UUID.randomUUID().toString()

fun refreshTokenExpiryEpochMillis(): Long = System.currentTimeMillis() + REFRESH_TOKEN_TTL_DAYS * 24 * 60 * 60_000

fun hashPassword(password: String): String = BCrypt.withDefaults().hashToString(12, password.toCharArray())

fun verifyPassword(password: String, hash: String): Boolean = BCrypt.verifyer().verify(password.toCharArray(), hash).verified

data class GoogleUserInfo(val sub: String, val email: String, val name: String?, val pictureUrl: String?)

// Web Client ID из Google Cloud Console — тот же самый, что передан в
// GoogleSignInOptions.requestIdToken(...) на Android-клиенте. Без него нельзя
// проверить подпись Google ID-токена.
private val googleOAuthClientId: String? = System.getenv("GOOGLE_OAUTH_CLIENT_ID")

private val googleIdTokenVerifier: GoogleIdTokenVerifier? by lazy {
    val clientId = googleOAuthClientId ?: return@lazy null
    GoogleIdTokenVerifier
        .Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
        .setAudience(listOf(clientId))
        .build()
}

/** @throws IllegalStateException если GOOGLE_OAUTH_CLIENT_ID не задан — это ошибка конфигурации сервера. */
fun verifyGoogleIdToken(idTokenString: String): GoogleUserInfo? {
    val verifier =
        googleIdTokenVerifier
            ?: error(
                "Не задана переменная окружения GOOGLE_OAUTH_CLIENT_ID — без неё нельзя проверить " +
                    "Google ID-токен. Нужен Web Client ID из Google Cloud Console (тот же, что указан " +
                    "в GoogleSignInOptions.requestIdToken(...) на Android-клиенте).",
            )

    val idToken: GoogleIdToken = verifier.verify(idTokenString) ?: return null
    val payload = idToken.payload
    return GoogleUserInfo(
        sub = payload.subject,
        email = payload.email ?: "",
        name = payload["name"] as? String,
        pictureUrl = payload["picture"] as? String,
    )
}

fun Application.configureSecurity() {
    install(Authentication) {
        jwt(JWT_AUTH_PROVIDER_NAME) {
            realm = "memify-backend"
            verifier(jwtVerifier)
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
}

/** Id текущего пользователя (sub-claim JWT) — удобный доступ из роутов вместо boilerplate. */
fun ApplicationCall.currentUserId(): String? = principal<JWTPrincipal>()?.payload?.subject
