
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.polina.memify"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.auth.jwt)
    implementation(ktorLibs.server.callLogging)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.netty)
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.0")

    // HTTP-клиент для синхронизации шаблонов с Imgflip Meme Generator API (get_memes)
    implementation("io.ktor:ktor-client-core:3.5.0")
    implementation("io.ktor:ktor-client-cio:3.5.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.5.0")
    implementation(libs.logback.classic)

    // Exposed — официальный Kotlin SQL-фреймворк от JetBrains, актуальная стабильная версия 1.0
    // https://blog.jetbrains.com/kotlin/2026/01/exposed-1-0-is-now-available/
    implementation("org.jetbrains.exposed:exposed-core:1.0.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.0.0")
    implementation("org.jetbrains.exposed:exposed-java-time:1.0.0")

    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0") // пул соединений с БД

    // S3-совместимый клиент — работает с Яндекс Object Storage без изменений в коде
    implementation(platform("software.amazon.awssdk:bom:2.29.1"))
    implementation("software.amazon.awssdk:s3")

    // Своя авторизация вместо Firebase Auth:
    // - JWT (ktor-server-auth-jwt выше уже подключает валидацию, но для явной генерации
    //   токенов подключаем java-jwt напрямую)
    implementation("com.auth0:java-jwt:4.4.0")
    // - хеширование паролей
    implementation("at.favre.lib:bcrypt:0.10.2")
    // - проверка Google ID-токена напрямую (без Firebase) для входа через Google
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.2")

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}
