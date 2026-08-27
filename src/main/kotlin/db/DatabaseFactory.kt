package com.polina.memify.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// Значения по умолчанию — это то, что мы настроили локально в части 2 гайда
// (Postgres.app, пользователь memify / memify_local_password, база memify).
// На реальном сервере переопредели через переменные окружения DB_JDBC_URL / DB_USER / DB_PASSWORD.
fun Application.configureDatabase() {
    val jdbcUrlEnv = System.getenv("DB_JDBC_URL") ?: "jdbc:postgresql://localhost:5432/memify"
    val dbUser = System.getenv("DB_USER") ?: "memify"
    val dbPassword = System.getenv("DB_PASSWORD") ?: "memify_local_password"

    val config = HikariConfig().apply {
        jdbcUrl = jdbcUrlEnv
        username = dbUser
        password = dbPassword
        maximumPoolSize = 10
        driverClassName = "org.postgresql.Driver"
    }
    val dataSource = HikariDataSource(config)
    val db = Database.connect(dataSource)

    // SchemaUtils.create создаёт таблицу, только если её ещё нет — новые колонки
    // (например password_hash), появившиеся в схеме позже, в уже существующую таблицу
    // он не добавляет. Из-за этого локальная база могла отстать от Tables.kt и падать
    // с "column ... does not exist". createMissingTablesAndColumns дополнительно
    // доливает недостающие колонки/таблицы — этого достаточно, пока это dev-база без
    // миграций. Как только появится реальная база с данными — переходи на нормальные
    // миграции (Flyway), иначе на не-dev окружении так же легко случайно потерять данные
    // при изменении схемы.
    transaction(db) {
        SchemaUtils.createMissingTablesAndColumns(
            Users, Templates, TemplateFavourites, Posts, PostLikes, RefreshTokens, PasswordResetTokens,
        )
    }
}
