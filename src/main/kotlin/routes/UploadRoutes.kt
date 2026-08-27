package com.polina.memify.routes

import com.polina.memify.storage.BUCKET_NAME
import com.polina.memify.storage.PUBLIC_BUCKET_URL
import com.polina.memify.storage.s3Client
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.PutObjectRequest

fun Route.uploadRoutes() {
    // Загрузка картинок — тоже только для залогиненных (свой JWT вместо Firebase-токена).
    authenticate("jwt-auth") {
        post("/upload") {
            val multipart = call.receiveMultipart()
            var uploadedUrl: String? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val fileName = "${System.currentTimeMillis()}_${part.originalFileName}"
                    val bytes = part.provider().toByteArray()

                    s3Client.putObject(
                        PutObjectRequest.builder()
                            .bucket(BUCKET_NAME)
                            .key(fileName)
                            .contentType(part.contentType?.toString())
                            .build(),
                        RequestBody.fromBytes(bytes),
                    )

                    uploadedUrl = "$PUBLIC_BUCKET_URL/$fileName"
                }
                part.dispose()
            }

            call.respond(mapOf("url" to uploadedUrl))
        }
    }
}
