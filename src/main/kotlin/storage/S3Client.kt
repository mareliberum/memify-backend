package com.polina.memify.storage

import com.polina.memify.env

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

// Имя бакета, который ты создала в Яндекс Object Storage. Поменяй, если у тебя он называется иначе.
const val BUCKET_NAME = "memify-images"
const val PUBLIC_BUCKET_URL = "https://storage.yandexcloud.net/$BUCKET_NAME"

// Ключи не хардкодим — берём из переменных окружения (key_id / secret статического ключа
// сервисного аккаунта, часть 2 гайда). "by lazy" — чтобы сервер не падал при старте,
// если переменные ещё не заданы, а падал только при реальной попытке что-то загрузить.
val s3Client: S3Client by lazy {
    val accessKey = env("YC_ACCESS_KEY_ID")
        ?: error("Не задана переменная окружения YC_ACCESS_KEY_ID")
    val secretKey = env("YC_SECRET_KEY")
        ?: error("Не задана переменная окружения YC_SECRET_KEY")

    S3Client.builder()
        .endpointOverride(URI.create("https://storage.yandexcloud.net"))
        .region(Region.of("ru-central1")) // формальное значение для SDK, у Yandex Object Storage один регион
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
        )
        .build()
}
