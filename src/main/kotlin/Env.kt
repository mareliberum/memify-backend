package com.polina.memify

import java.io.File

// System.getenv() видит только настоящие переменные окружения ОС — а .env-файл сам по
// себе никто не читает (это работает "само" только внутри docker-compose, который сам
// прокидывает .env в переменные окружения контейнера). Если бэкенд запущен из IDE или
// через `./gradlew run`, .env остаётся просто текстовым файлом, который никто не видит,
// и System.getenv("...") возвращает null, даже если значение вписано в .env.
//
// env(name) сначала проверяет настоящую переменную окружения (так работает в Docker/на
// сервере через systemd EnvironmentFile), а если её нет — берёт значение из .env файла
// в корне проекта, распарсив его вручную (простой формат KEY=VALUE, строки с # и пустые
// строки пропускаются).
private val dotenvValues: Map<String, String> by lazy {
    val file = File(".env")
    if (!file.exists()) {
        emptyMap()
    } else {
        file.readLines()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                val separatorIndex = line.indexOf('=')
                if (separatorIndex == -1) return@mapNotNull null
                val key = line.substring(0, separatorIndex).trim()
                val value = line.substring(separatorIndex + 1).trim().trim('"')
                key to value
            }
            .toMap()
    }
}

fun env(name: String): String? = System.getenv(name) ?: dotenvValues[name]
