package ai.kilocode.backend.migration

import ai.kilocode.connection.Transport
import ai.kilocode.connection.TransportException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [Transport]-based implementation of [LegacyMigrationBackend].
 *
 * Uses raw JSON payloads (provider auth, global config, session-import) built
 * exactly as needed, without any generated client types.
 *
 * All calls are synchronous blocking (runBlocking over the suspend transport).
 * The caller owns threading and error handling.
 */
class LegacyMigrationTransportBackend(
    private val transport: Transport,
) : LegacyMigrationBackend {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    private fun call(method: String, path: String, body: String? = null): String = runBlocking {
        try {
            transport.call(method, path, body)
        } catch (e: TransportException) {
            throw RuntimeException(
                "${method.lowercase()} $path failed: HTTP ${e.status} — ${e.body?.take(400)}",
                e,
            )
        }
    }


    // -----------------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------------

    override fun setAuth(provider: String, auth: JsonObject) {
        try {
            call("PUT", "/auth/$provider", auth.toString())
        } catch (e: RuntimeException) {
            throw RuntimeException("setAuth failed for $provider: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Global config
    // -----------------------------------------------------------------------

    override fun updateGlobalConfig(config: JsonObject) {
        try {
            call("PATCH", "/global/config", config.toString())
        } catch (e: RuntimeException) {
            throw RuntimeException("updateGlobalConfig failed: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Session existence check
    // -----------------------------------------------------------------------

    override fun sessionExists(id: String): Boolean = try {
        call("GET", "/session/$id")
        true
    } catch (e: TransportException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    // -----------------------------------------------------------------------
    // Session import
    // -----------------------------------------------------------------------

    override fun importProject(project: JsonObject): String {
        val dir = project["worktree"]?.jsonPrimitive?.content ?: ""
        val raw = try {
            call("POST", "/kilocode/session-import/project?directory=${encode(dir)}", project.toString())
        } catch (e: RuntimeException) {
            throw RuntimeException("importProject failed: ${e.message}", e)
        }
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        return obj?.get("id")?.jsonPrimitive?.content
            ?: project["id"]?.jsonPrimitive?.content
            ?: ""
    }

    override fun importSession(session: JsonObject): LegacyImportResult {
        val dir = session["directory"]?.jsonPrimitive?.content ?: ""
        val raw = try {
            call("POST", "/kilocode/session-import/session?directory=${encode(dir)}", session.toString())
        } catch (e: RuntimeException) {
            throw RuntimeException("importSession failed: ${e.message}", e)
        }
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val id = obj?.get("id")?.jsonPrimitive?.content
            ?: session["id"]?.jsonPrimitive?.content
            ?: ""
        val skipped = obj?.get("skipped")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return LegacyImportResult(id = id, skipped = skipped)
    }

    override fun importMessage(message: JsonObject) {
        val sessionId = message["sessionID"]?.jsonPrimitive?.content ?: ""
        try {
            call("POST", "/kilocode/session-import/message?sessionID=${encode(sessionId)}", message.toString())
        } catch (e: RuntimeException) {
            throw RuntimeException("importMessage failed: ${e.message}", e)
        }
    }

    override fun importPart(part: JsonObject) {
        val sessionId = part["sessionID"]?.jsonPrimitive?.content ?: ""
        try {
            call("POST", "/kilocode/session-import/part?sessionID=${encode(sessionId)}", part.toString())
        } catch (e: RuntimeException) {
            throw RuntimeException("importPart failed: ${e.message}", e)
        }
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
