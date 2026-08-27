package com.polina.memify

import com.polina.memify.routes.authRoutes
import com.polina.memify.routes.postsRoutes
import com.polina.memify.routes.templatesRoutes
import com.polina.memify.routes.uploadRoutes
import com.polina.memify.routes.usersRoutes
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Memify backend is running")
        }
        authRoutes()
        usersRoutes()
        postsRoutes()
        templatesRoutes()
        uploadRoutes()
    }
}
