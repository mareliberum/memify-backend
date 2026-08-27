# ---- Стадия сборки ----
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Сначала копируем только файлы, нужные для резолва зависимостей —
# это отдельный слой Docker-кэша, который не будет пересобираться
# при каждом изменении кода в src/.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
RUN chmod +x gradlew

COPY src ./src

# buildFatJar — таск из официального Ktor Gradle-плагина (io.ktor.plugin),
# который уже подключён в build.gradle.kts. Собирает один самодостаточный
# .jar со всеми зависимостями внутри (build/libs/backend-all.jar).
RUN ./gradlew --no-daemon buildFatJar

# ---- Стадия запуска ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
