package ai.kilocode.cscloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

    fun rewrite(request: Request, prefix: String = "", roots: List<Path> = emptyList()): Request {
        val full = request.url.encodedPath
        val path = full.removePrefix(prefix).ifEmpty { "/" }
        val route = route(path)
        val workspacePath = request.url.queryParameter(workspace)
        val builder = request.url.newBuilder().encodedPath("${prefix.trimEnd('/')}$route")
        if (request.url.queryParameter(workspace) != null) builder.removeAllQueryParameters(workspace)
        val result = request.newBuilder().url(builder.build())
        if (path == "/session" || path.startsWith("/session/")) {
            val value = workspacePath?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("workspace directory is required for session requests")
            result.header(workspaceHeader, workspace(value, roots).toString())
        } else if (workspacePath != null) {
            result.header(workspaceHeader, workspace(workspacePath, roots).toString())
        }
        return result.build()
    }

    fun interceptor(prefix: String = "", roots: () -> List<Path> = { emptyList() }): Interceptor = Interceptor { chain ->
        val path = chain.request().url.encodedPath.removePrefix(prefix).ifEmpty { "/" }
        val body = when (path) {
            "/kilo/profile" -> return@Interceptor local(chain.request(), 401, "{}")
            "/kilo/notifications", "/config/warnings", "/skill" -> "[]"
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
        val data = when {
            response.request.url.encodedPath.endsWith("/api/v1/runtime/health") -> {
                val health = CsCloudHealth.parseHealth(text)
                buildJsonObject {
                    put("healthy", health.healthy)
                    put("version", health.version)
                    put("capabilities", JsonArray(health.capabilities.sorted().map(::JsonPrimitive)))
                }.toString()
            }
            response.request.url.encodedPath.endsWith("/api/v1/agents/models") -> models(text)
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

    private fun route(path: String): String = when {
        path == "/session" -> "/api/v1/conversations"
        path.matches(Regex("/session/[^/]+/prompt_async")) -> "/api/v1/conversations/${path.split('/')[2]}/prompt/async"
        path.matches(Regex("/session/[^/]+/message")) -> "/api/v1/conversations/${path.split('/')[2]}/messages"
        path.startsWith("/session/") -> "/api/v1/conversations/${path.removePrefix("/session/")}"
        path == "/global/event" -> "/api/v1/events"
        path.startsWith("/permission") -> "/api/v1/permissions${path.removePrefix("/permission")}"
        path.startsWith("/question") -> "/api/v1/questions${path.removePrefix("/question")}"
        path == "/global/health" -> "/api/v1/runtime/health"
        path == "/global/config" -> "/api/v1/agents/config"
        path == "/config" -> "/api/v1/agents/config"
        path == "/provider" -> "/api/v1/agents/models"
        path == "/agent" -> "/api/v1/agents/session-modes"
        path == "/command" -> "/api/v1/agents/commands"
        path == "/path" -> "/api/v1/runtime/path"
        path == "/find/file" -> "/api/v1/runtime/find/file"
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
