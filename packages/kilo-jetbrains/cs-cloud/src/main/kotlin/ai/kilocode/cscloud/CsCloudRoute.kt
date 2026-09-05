package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.nio.file.Path
import java.nio.file.Files

object CsCloudRoute {
    private const val workspace = "directory"
    private const val workspaceHeader = "X-Workspace-Directory"
    private const val clientHeader = "X-Session-Client"
    private const val client = "kilo-jetbrains"
    internal const val BUILTIN_PREFIX = "builtin:"

    fun rewrite(request: Request, prefix: String = "", roots: List<Path> = emptyList()): Request {
        val path = clientPath(request.url.encodedPath, prefix)
        val route = route(path)
        val workspacePath = request.url.queryParameter(workspace)
        val builder = request.url.newBuilder().encodedPath("${prefix.trimEnd('/')}$route")
        if (request.url.queryParameter(workspace) != null) builder.removeAllQueryParameters(workspace)
        val result = request.newBuilder().url(builder.build())
        if (
            path == "/session" || path.startsWith("/session/") ||
            path == "/conversations" || path.startsWith("/conversations/")
        ) {
            val value = workspacePath?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("workspace directory is required for session requests")
            result.header(workspaceHeader, workspace(value, roots).toString())
            result.header(clientHeader, client)
        } else if (workspacePath != null) {
            result.header(workspaceHeader, workspace(workspacePath, roots).toString())
        }
        return result.build()
    }

    fun interceptor(prefix: String = "", roots: () -> List<Path> = { emptyList() }): Interceptor = Interceptor { chain ->
        val path = clientPath(chain.request().url.encodedPath, prefix)
        val body = when (path) {
            "/kilo/profile" -> return@Interceptor local(chain.request(), 401, "{}")
            "/kilo/notifications", "/config/warnings" -> "[]"
            "/provider/auth" -> "{}"
            else -> null
        }
        if (body != null) return@Interceptor local(chain.request(), 200, body)
        chain.proceed(rewrite(chain.request(), prefix, roots()))
    }

    fun responseInterceptor(): Interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body ?: return@Interceptor response
        val text = body.string()
        if (!response.isSuccessful) throw CsCloudRequestException.fromResponse(response, text)
        val path = apiPath(response.request.url.encodedPath)
        val data = when {
            path == "/api/v1/runtime/health" -> {
                val health = CsCloudHealth.parseHealth(text)
                buildJsonObject {
                    put("healthy", health.healthy)
                    put("version", health.version)
                    put("capabilities", JsonArray(health.capabilities.sorted().map(::JsonPrimitive)))
                }.toString()
            }
            path == "/api/v1/agents/models" -> models(text)
            path == "/api/v1/agents/session-modes" -> agents(text)
            path == "/api/v1/agents/commands" -> commands(text)
            path == "/api/v1/conversations" -> conversations(text, list = response.request.method == "GET")
            isConversationDetail(path) -> conversations(text, list = false)
            else -> text
        }
        response.newBuilder().body(data.toResponseBody(body.contentType())).build()
    }

    private fun models(raw: String): String {
        val root = Json.parseToJsonElement(raw).jsonObject
        val all = root["connected"]?.jsonArray ?: JsonArray(emptyList())
        val connected = JsonArray(all.mapNotNull { it.jsonObject["id"] })
        val defaults = buildJsonObject {
            val provider = all.firstOrNull()?.jsonObject ?: return@buildJsonObject
            val id = provider["id"]?.jsonPrimitive?.contentOrNull ?: return@buildJsonObject
            val model = provider["default_model"]?.jsonPrimitive?.contentOrNull ?: return@buildJsonObject
            put("build", "$id/$model")
        }
        return buildJsonObject {
            put("all", all)
            put("default", defaults)
            put("connected", connected)
            put("failed", JsonArray(emptyList()))
        }.toString()
    }

    /** CSC versions return either an array or a `value` envelope for this list. */
    private fun agents(raw: String): String {
        val root = Json.parseToJsonElement(raw)
        val value = (root as? JsonObject)?.get("value") ?: root
        val array = value as? JsonArray ?: return value.toString()
        return JsonArray(array.map { item ->
            val obj = item as? JsonObject ?: return@map item
            JsonObject(buildMap {
                putAll(obj)
                if (obj["permission"] == null || obj["permission"] is JsonNull) put("permission", JsonArray(emptyList()))
                if (obj["options"] == null || obj["options"] is JsonNull) put("options", JsonObject(emptyMap()))
            })
        }).toString()
    }

    /** CSC serves skills through the commands list: tag every entry as a read-only builtin. */
    private fun commands(raw: String): String {
        val root = Json.parseToJsonElement(raw)
        val value = (root as? JsonObject)?.get("value") ?: root
        val array = value as? JsonArray ?: return value.toString()
        return JsonArray(array.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@map obj
            JsonObject(buildMap {
                putAll(obj)
                if (obj["location"] == null || obj["location"] is JsonNull) put("location", JsonPrimitive("$BUILTIN_PREFIX$name"))
                if (obj["builtin"] == null || obj["builtin"] is JsonNull) put("builtin", JsonPrimitive(true))
            })
        }).toString()
    }

    private fun conversations(raw: String, list: Boolean): String {
        val root = Json.parseToJsonElement(raw)
        val value = (root as? JsonObject)?.get("value") ?: root
        if (list) {
            val array = value as? JsonArray ?: return value.toString()
            return JsonArray(array.map(::conversation)).toString()
        }
        return conversation(value).toString()
    }

    private fun isConversationDetail(path: String): Boolean {
        val prefix = "/api/v1/conversations/"
        val id = path.removePrefix(prefix)
        return path.startsWith(prefix) && id.isNotEmpty() && !id.contains('/')
    }

    private fun apiPath(raw: String): String {
        val path = raw.trimEnd('/').ifEmpty { "/" }
        val index = path.lastIndexOf("/api/v1/")
        return if (index >= 0) path.substring(index) else path
    }

    private fun clientPath(raw: String, prefix: String): String =
        raw.removePrefix(prefix).trimEnd('/').ifEmpty { "/" }

    private fun conversation(value: JsonElement): JsonElement {
        val obj = value as? JsonObject ?: return value
        val id = obj["id"] ?: obj["session_id"]
        val model = (obj["model"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return JsonObject(buildMap {
            putAll(obj)
            if (id != null) put("id", id)
            if (obj["directory"] == null && obj["cwd"] != null) put("directory", obj.getValue("cwd"))
            if (obj["projectID"] == null) put("projectID", JsonPrimitive(""))
            if (obj["title"] == null) put("title", JsonPrimitive(""))
            if (obj["version"] == null) put("version", JsonPrimitive(""))
            if (obj["time"] == null) {
                val created = obj["created_at"] ?: JsonPrimitive(0)
                put("time", buildJsonObject {
                    put("created", created)
                    put("updated", obj["last_active_at"] ?: created)
                })
            }
            if (model != null) {
                put("model", buildJsonObject {
                    put("id", model)
                    put("providerID", "")
                })
            }
        })
    }

    private fun route(path: String): String = when {
        path == "/session" -> "/api/v1/conversations"
        path.matches(Regex("/session/[^/]+/prompt_async")) -> "/api/v1/conversations/${path.split('/')[2]}/prompt/async"
        path.matches(Regex("/session/[^/]+/message")) -> "/api/v1/conversations/${path.split('/')[2]}/messages"
        path.startsWith("/session/") -> "/api/v1/conversations/${path.removePrefix("/session/")}"
        path == "/conversations" -> "/api/v1/conversations"
        path.startsWith("/conversations/") -> "/api/v1${path}"
        path == "/global/event" -> "/api/v1/events"
        path.startsWith("/permission") -> "/api/v1/permissions${path.removePrefix("/permission")}"
        path.startsWith("/question") -> "/api/v1/questions${path.removePrefix("/question")}"
        path == "/global/health" -> "/api/v1/runtime/health"
        path == "/global/config" -> "/api/v1/agents/config"
        path == "/config" -> "/api/v1/agents/config"
        path == "/provider" -> "/api/v1/agents/models"
        path == "/agent" -> "/api/v1/agents/session-modes"
        path == "/command" -> "/api/v1/agents/commands"
        path == "/skill" -> "/api/v1/agents/commands"
        path == "/path" -> "/api/v1/runtime/path"
        path == "/find/file" -> "/api/v1/runtime/find/file"
        path == "/commit-message" -> "/api/v1/commit-message"
        else -> path
    }

    private fun workspace(value: String, roots: List<Path>): Path {
        val path = canonical(Path.of(value))
        if (roots.isEmpty()) return path
        val allowed = roots.map(::canonical)
        require(allowed.any { path == it || path.startsWith(it) }) { "workspace directory is outside the active project" }
        return path
    }

    private fun canonical(value: Path): Path {
        val path = value.toAbsolutePath().normalize()
        if (Files.exists(path)) return path.toRealPath()
        val missing = mutableListOf<Path>()
        var parent = path
        while (!Files.exists(parent)) {
            val name = parent.fileName ?: break
            missing.add(name)
            parent = parent.parent ?: break
        }
        return missing.asReversed().fold(parent.toRealPath()) { root, part -> root.resolve(part) }.normalize()
    }

    private fun local(request: Request, status: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message(if (status == 200) "OK" else "Unauthorized")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

fun rewrite(request: Request): Request = CsCloudRoute.rewrite(request)
