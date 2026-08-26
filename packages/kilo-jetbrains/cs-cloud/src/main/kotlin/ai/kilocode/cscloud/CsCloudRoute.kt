package ai.kilocode.cscloud

/**
 * Maps plugin-side routes to cs-cloud control-plane routes (design §5.1):
 *  - `/session...`            → `/api/v1/conversations...`
 *  - `/session/{id}/prompt_async` → `/api/v1/conversations/{id}/prompt/async`
 *  - `/session/{id}/message`  → `/api/v1/conversations/{id}/messages`
 *  - `/global/event`          → `/api/v1/events`
 *  - `/permission...`         → `/api/v1/permissions...`
 *  - `/question...`           → `/api/v1/questions...`
 *  - `/global/health`         → `/api/v1/runtime/health`
 */
object CsCloudRoute {

    private val promptAsync = Regex("/session/[^/]+/prompt_async")
    private val message = Regex("/session/[^/]+/message")

    fun mapPath(path: String): String = when {
        path == "/session" || path == "/session/" -> "/api/v1/conversations"
        path == "/global/event" -> "/api/v1/events"
        path == "/global/health" -> "/api/v1/runtime/health"
        promptAsync.matches(path) -> "/api/v1/conversations/${path.split('/')[2]}/prompt/async"
        message.matches(path) -> "/api/v1/conversations/${path.split('/')[2]}/messages"
        path.startsWith("/session/") -> "/api/v1/conversations/${path.removePrefix("/session/")}"
        path.startsWith("/permission") -> "/api/v1/permissions${path.removePrefix("/permission")}"
        path.startsWith("/question") -> "/api/v1/questions${path.removePrefix("/question")}"
        else -> path
    }
}
