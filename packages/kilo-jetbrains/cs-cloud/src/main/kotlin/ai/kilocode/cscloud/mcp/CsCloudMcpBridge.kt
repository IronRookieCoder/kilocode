package ai.kilocode.cscloud.mcp

import ai.kilocode.backend.app.CapabilityReleaseReason
import ai.kilocode.backend.app.CapabilityResult
import ai.kilocode.backend.app.KiloSessionCapabilities
import ai.kilocode.cscloud.CsCloudEndpoint
import ai.kilocode.log.KiloLog
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal suspend fun runLease(
    ready: CompletableDeferred<IdeMcpTransport>,
    label: String,
    log: KiloLog,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (error: CancellationException) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
        throw error
    } catch (error: Throwable) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
        log.warn(label, error)
    }
}

class CsCloudMcpBridge(
    private val scope: CoroutineScope,
    private val endpoint: () -> CsCloudEndpoint?,
    private val client: () -> OkHttpClient?,
    private val factory: IdeMcpSessionFactory?,
    private val log: KiloLog,
) : KiloSessionCapabilities {
    private data class Lease(val workspace: String, val generation: String, val tools: Set<String>, val job: Job, val epoch: String)
    private val leases = ConcurrentHashMap<String, Lease>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    override suspend fun ensure(id: String, directory: String): CapabilityResult = locks.getOrPut(id) { Mutex() }.withLock {
        val workspace = project(directory) ?: return@withLock CapabilityResult.Unavailable("project_not_open")
        if (!supported()) return@withLock CapabilityResult.Unavailable("ide_capability_unsupported")
        val source = factory ?: return@withLock CapabilityResult.Unavailable("mcp_plugin_unavailable")
        val tools = source.enabled(COSTRICT_IDE_TOOLS)
        if (tools.isEmpty()) {
            releaseLocked(id)
            return@withLock CapabilityResult.Unavailable("tools_disabled")
        }
        val epoch = endpoint()?.base ?: return@withLock CapabilityResult.Unavailable("ide_capability_unsupported")
        leases[id]?.takeIf { it.workspace == workspace && it.tools == tools && it.epoch == epoch && it.job.isActive }?.let {
            return@withLock CapabilityResult.Ready(it.generation, it.tools)
        }
        val generation = UUID.randomUUID().toString()
        val ready = CompletableDeferred<IdeMcpTransport>()
        val job = scope.launch {
            runLease(ready, "IDE MCP lease failed conversation=${hash(id)} generation=${hash(generation)}", log) {
                source.open(tools) { transport ->
                    ready.complete(transport)
                    awaitCancellation()
                }
            }
        }
        val transport = runCatching { withTimeout(30_000) { ready.await() } }.getOrElse {
            job.cancel()
            return@withLock CapabilityResult.Unavailable(code(it))
        }
        if (!bind(id, workspace, generation, transport, tools)) {
            job.cancel()
            return@withLock CapabilityResult.Unavailable("ide_capability_bind_failed")
        }
        val old = leases.put(id, Lease(workspace, generation, tools, job, epoch))
        old?.job?.cancel()
        old?.let { clear(id, it.generation, it.workspace) }
        CapabilityResult.Ready(generation, tools)
    }

    override suspend fun release(id: String, reason: CapabilityReleaseReason) = locks.getOrPut(id) { Mutex() }.withLock { releaseLocked(id) }
    override suspend fun releaseAll(reason: CapabilityReleaseReason) { leases.keys.toList().forEach { release(it, reason) } }

    private suspend fun releaseLocked(id: String) { leases.remove(id)?.let { lease -> lease.job.cancel(); clear(id, lease.generation, lease.workspace) } }

    private suspend fun bind(id: String, workspace: String, generation: String, transport: IdeMcpTransport, tools: Set<String>): Boolean = withContext(Dispatchers.IO) {
        val base = endpoint()?.base ?: return@withContext false
        val http = client() ?: return@withContext false
        val spec = IdeMcpCapabilitySpec(
            generation = generation,
            workspace = workspace,
            transport = IdeMcpTransportSpec(url = "http://127.0.0.1:${transport.port}/stream", headers = mapOf(transport.authHeader to transport.token, "IJ_MCP_SERVER_PROJECT_PATH" to workspace)),
            tools = tools.toList().sorted(),
        )
        val url = base.toHttpUrl().newBuilder().addPathSegments("api/v1/conversations").addPathSegment(id).addPathSegments("capabilities/ide").build()
        val request = Request.Builder().url(url).header("X-Workspace-Directory", workspace)
            .put(json.encodeToString(spec).toRequestBody("application/json".toMediaType())).build()
        runCatching { http.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private suspend fun supported(): Boolean = withContext(Dispatchers.IO) {
        val base = endpoint()?.base ?: return@withContext false
        val http = client() ?: return@withContext false
        val request = Request.Builder().url("$base/global/health").get().build()
        runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                response.isSuccessful && Json.parseToJsonElement(body).jsonObject["capabilities"]
                    ?.jsonArray?.any { it.jsonPrimitive.content == "conversation_ide_capability_v1" } == true
            }
        }.getOrDefault(false)
    }

    private suspend fun clear(id: String, generation: String, workspace: String) = withContext(Dispatchers.IO) {
        val base = endpoint()?.base ?: return@withContext
        val http = client() ?: return@withContext
        val url = base.toHttpUrl().newBuilder().addPathSegments("api/v1/conversations").addPathSegment(id)
            .addPathSegments("capabilities/ide").addQueryParameter("generation", generation).build()
        runCatching { http.newCall(Request.Builder().url(url).header("X-Workspace-Directory", workspace).delete().build()).execute().close() }
            .onFailure { log.warn("IDE MCP release failed conversation=${hash(id)} generation=${hash(generation)}", it) }
    }

    private fun project(directory: String): String? {
        val input = canonical(directory) ?: return null
        val matches = ProjectManager.getInstance().openProjects.filterNot { it.isDefault || it.isDisposed }.mapNotNull { it.basePath?.let(::canonical) }.filter { it == input }
        return matches.singleOrNull()
    }

    private fun canonical(value: String): String? = runCatching { Path.of(value).toRealPath().toString() }.getOrElse { runCatching { Path.of(value).toAbsolutePath().normalize().toString() }.getOrNull() }
    private fun code(error: Throwable) = error.message?.takeIf { it.matches(Regex("[a-z_]+")) } ?: "mcp_listener_failed"
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
