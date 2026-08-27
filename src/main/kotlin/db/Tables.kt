package com.polina.memify.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant
import java.util.UUID

// Схема повторяет три коллекции Firestore, которые сейчас использует приложение:
// templates, posts, users — плюс отдельные таблицы для лайков/избранного (в Firestore
// это были подколлекции/массивы, в реляционной БД для связей "многие ко многим"
// делаются отдельные таблицы).
//
// passwordHash/googleSub/phone/tsi, RefreshTokens и PasswordResetTokens добавлены
// при переходе со связки "Firebase Auth + Firestore" на собственную авторизацию
// (email/password + Google) поверх этого бэкенда.

object Users : Table("users") {
    val id = varchar("id", 128) // раньше здесь был firebase uid, теперь — свой UUID
    val username = varchar("username", 64)
    val email = varchar("email", 256)
    val photoUrl = varchar("photo_url", 512).nullable()
    // Хеш пароля (bcrypt). Null для пользователей, которые вошли только через Google.
    val passwordHash = varchar("password_hash", 256).nullable()
    // Google "sub" (уникальный id пользователя в Google) — для входа через Google.
    val googleSub = varchar("google_sub", 128).nullable().uniqueIndex()
    val phone = varchar("phone", 32).nullable()
    val tsi = integer("tsi").default(0) // trust score index
    override val primaryKey = PrimaryKey(id)
}

object Templates : Table("templates") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val name = varchar("name", 256)
    val url = varchar("url", 1024)
    val width = integer("width")
    val height = integer("height")
    val usedCount = integer("used_count").default(0)
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    override val primaryKey = PrimaryKey(id)
}

object TemplateFavourites : Table("template_favourites") {
    val templateId = reference("template_id", Templates.id)
    val userId = reference("user_id", Users.id)
    override val primaryKey = PrimaryKey(templateId, userId)
}

object Posts : Table("posts") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val authorId = reference("author_id", Users.id)
    val imageUrl = varchar("image_url", 1024)
    // Шаблон, из которого сделан пост — раньше был только в клиентской модели,
    // в таблице отсутствовал (см. план миграции).
    val templateId = reference("template_id", Templates.id).nullable()
    val width = integer("width")
    val height = integer("height")
    val createdAt = timestamp("created_at").clientDefault { Instant.now() }
    override val primaryKey = PrimaryKey(id)
}

object PostLikes : Table("post_likes") {
    val postId = reference("post_id", Posts.id)
    val userId = reference("user_id", Users.id)
    override val primaryKey = PrimaryKey(postId, userId)
}

// Refresh-токены собственной JWT-авторизации. Храним хеш токена (а не сам токен),
// чтобы утечка базы не давала утечку годных токенов напрямую.
object RefreshTokens : Table("refresh_tokens") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val userId = reference("user_id", Users.id)
    val tokenHash = varchar("token_hash", 128)
    val expiresAt = timestamp("expires_at")
    val revoked = bool("revoked").default(false)
    override val primaryKey = PrimaryKey(id)
}

// Одноразовые токены для сброса пароля (POST /auth/forgot-password -> /auth/reset-password).
object PasswordResetTokens : Table("password_reset_tokens") {
    val id = varchar("id", 64).clientDefault { UUID.randomUUID().toString() }
    val userId = reference("user_id", Users.id)
    val tokenHash = varchar("token_hash", 128)
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    override val primaryKey = PrimaryKey(id)
}
