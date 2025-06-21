package github.rikacelery

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.module() {
    configureRouting()
}


fun Application.configureRouting() {

    routing {
        get("/add") {
        }
        get("/remove") {
        }
        get("/start") {
        }
        get("/stop") {
        }
        get("/activate") {
        }
        get("/deactivate") {
        }
        get("/list") {
        }
    }
}
