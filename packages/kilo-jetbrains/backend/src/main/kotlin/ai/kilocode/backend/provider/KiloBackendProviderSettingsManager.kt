package ai.kilocode.backend.provider

import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.CustomModelFetchDto
import ai.kilocode.rpc.dto.CustomModelFetchResultDto
import ai.kilocode.rpc.dto.CustomProviderConfigDto
import ai.kilocode.rpc.dto.CustomProviderSaveDto
import ai.kilocode.rpc.dto.LoadErrorDto
import ai.kilocode.rpc.dto.ProviderAuthMethodDto
import ai.kilocode.rpc.dto.ProviderOAuthAuthorizeDto
import ai.kilocode.rpc.dto.ProviderOAuthCallbackDto
import ai.kilocode.rpc.dto.ProviderOAuthReadyDto
import ai.kilocode.rpc.dto.ProviderSettingsDto
import ai.kilocode.rpc.dto.ProviderSettingsProviderDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal class KiloBackendProviderSettingsManager(
    private val app: KiloBackendAppService,
) {
    companion object {
        private val LOG = KiloLog.create(KiloBackendProviderSettingsManager::class.java)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private const val FETCH_TIMEOUT_SECONDS = 15L

        /** Wire shape of `GET /provider`. */
        @Serializable
        private data class ProviderListResponse(
            val all: List<ProviderSettingsProviderDto> = emptyList(),
            val connected: List<String> = emptyList(),
            @SerialName("default") val defaultModels: Map<String, String> = emptyMap(),
        )

        /** Wire shape of the provider-scoped portion of `GET /global/config`. */
        @Serializable
        private data class ProviderScopedConfig(
            val provider: Map<String, CustomProviderConfigDto> = emptyMap(),
            val disabled_providers: List<String> = emptyList(),
            val enabled_providers: List<String> = emptyList(),
        )

        /** Wire shape of external OpenAI-compatible `/models` responses. */
        @Serializable
        private data class ExternalModelsResponse(
            val data: List<ExternalModel> = emptyList(),
        ) {
            @Serializable
            data class ExternalModel(val id: String = "")
        }
    }

    private val fetchClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS)).build()
    }

    internal data class ParsedConfig(
        val config: Map<String, CustomProviderConfigDto>,
        val disabled: List<String>,
        val enabled: List<String>,
    )

    suspend fun state(directory: String): ProviderSettingsDto {
        val start = System.currentTimeMillis()
        LOG.debug { "provider settings state: start dir=$directory" }
        app.awaitReady()
        val errors = mutableListOf<LoadErrorDto>()
        val providers = load("providers", errors) {
            json.decodeFromString(ProviderListResponse.serializer(), get("/provider?directory=${enc(directory)}"))
        }
        val auth = load("provider_auth", errors) {
            json.decodeFromString(
                MapSerializer(String.serializer(), ListSerializer(ProviderAuthMethodDto.serializer())),
                get("/provider/auth?directory=${enc(directory)}"),
            )
        } ?: emptyMap()
        val empty = ParsedConfig(emptyMap(), emptyList(), emptyList())
        val global = load("global_config", errors) {
            parsed(get("/global/config"))
        } ?: empty
        val local = load("workspace_config", errors) {
            parsed(get("/config?directory=${enc(directory)}"))
        } ?: empty
        val cfg = scopedConfig(global.config, local.config)
        val disabled = (global.disabled + local.disabled).distinct().sorted()
        val enabled = (global.enabled + local.enabled).distinct().sorted()
        val disabledScopes = scopedIds(global.disabled, local.disabled)
        val enabledScopes = scopedIds(global.enabled, local.enabled)
        val result = ProviderSettingsDto(
            providers = providers?.all ?: emptyList(),
            connected = providers?.connected ?: emptyList(),
            defaults = providers?.defaultModels ?: emptyMap(),
            auth = auth,
            config = cfg,
            disabled = disabled,
            enabled = enabled,
            disabledScopes = disabledScopes,
            enabledScopes = enabledScopes,
            errors = errors,
        )
        result.providers.forEach { provider ->
            val configured = provider.id in result.connected || provider.key != null || provider.source == "config" || provider.id in result.config
            LOG.debug {
                "provider settings provider: id=${provider.id} source=${provider.source} connected=${provider.id in result.connected} configured=$configured disabled=${provider.id in result.disabled} enabled=${provider.id in result.enabled} hasKey=${provider.key != null} auth=${result.auth[provider.id].orEmpty().map { it.type }.distinct().joinToString(",")} config=${provider.id in result.config} models=${provider.models.size} description=${provider.description?.isNotBlank() == true} noteKey=${provider.metadata?.noteKey} icon=${provider.metadata?.icon} priority=${provider.metadata?.priority}"
            }
        }
        LOG.debug { "provider settings state: done dir=$directory took=${System.currentTimeMillis() - start}ms providers=${result.providers.size} connected=${result.connected.size} auth=${result.auth.size} errors=${result.errors.size}" }
        return result
    }

    private suspend fun <T> load(name: String, errors: MutableList<LoadErrorDto>, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            LOG.debug { "provider settings load failed: name=$name message=${e.message}" }
            errors.add(LoadErrorDto(resource = name, detail = e.message))
            null
        }
    }

    private suspend fun parsed(raw: String): ParsedConfig {
        val scoped = json.decodeFromString(ProviderScopedConfig.serializer(), raw)
        return ParsedConfig(scoped.provider, scoped.disabled_providers, scoped.enabled_providers)
    }

    private fun scopedConfig(global: Map<String, CustomProviderConfigDto>, local: Map<String, CustomProviderConfigDto>): Map<String, CustomProviderConfigDto> {
        return (global.mapValues { withScope(it.value, "global") } + local.mapValues { withScope(it.value, "workspace") })
    }

    private fun scopedIds(global: List<String>, local: List<String>): Map<String, List<String>> {
        return local.associateWith { listOf("workspace") } + global.associateWith { listOf("global") }
    }

    private fun withScope(cfg: CustomProviderConfigDto, scope: String): CustomProviderConfigDto =
        if (cfg.scope == scope) cfg else cfg.copy(scope = scope)

    // ------ request helpers ------

    private suspend fun get(path: String): String = app.transport().use { it.call("GET", path) }

    private suspend fun post(path: String, body: String? = null): String =
        app.transport().use { it.call("POST", path, body) }

    private suspend fun patch(path: String, body: String): String =
        app.transport().use { it.call("PATCH", path, body) }

    private suspend fun delete(path: String): String =
        app.transport().use { it.call("DELETE", path) }

    // ------ actions ------

    suspend fun connect(input: ai.kilocode.rpc.dto.ProviderConnectDto): Boolean {
        if (input.providerId.isBlank()) {
            throw IllegalArgumentException("providerId is required")
        }
        if (input.key.isBlank()) {
            throw IllegalArgumentException("key is required")
        }
        val body = buildJsonObject {
            put("type", "api")
            put("key", input.key)
            if (input.metadata.isNotEmpty()) {
                put("metadata", buildJsonObject { input.metadata.forEach { (k, v) -> put(k, v) } })
            }
        }.toString()
        val raw = post("/auth/${enc(input.providerId)}", body)
        return raw.trim().toBooleanStrictOrNull() ?: true
    }

    suspend fun authorize(input: ProviderOAuthAuthorizeDto): ProviderOAuthReadyDto {
        val body = buildJsonObject {
            val index = input.method.toLongOrNull()
            if (index != null) put("method", index) else put("method", input.method)
            if (input.inputs.isNotEmpty()) put("inputs", buildJsonObject { input.inputs.forEach { (k, v) -> put(k, v) } })
        }.toString()
        val raw = post("/provider/${enc(input.providerId)}/oauth/authorize", body)
        return json.decodeFromString(ProviderOAuthReadyDto.serializer(), raw)
    }

    suspend fun callback(input: ProviderOAuthCallbackDto): Boolean {
        val body = buildJsonObject {
            val index = input.method.toLongOrNull()
            if (index != null) put("method", index) else put("method", input.method)
            put("code", input.code)
        }.toString()
        val raw = post("/provider/${enc(input.providerId)}/oauth/callback", body)
        return raw.trim() != "false"
    }

    suspend fun disconnect(input: ai.kilocode.rpc.dto.ProviderDisconnectDto): Boolean {
        val raw = delete("/auth/${enc(input.providerId)}")
        return raw.trim() != "false"
    }

    suspend fun enable(input: ai.kilocode.rpc.dto.ProviderEnableDto): Boolean {
        val current = parsed(get("/global/config"))
        val merged = (current.disabled - input.providerId).sorted()
        val body = buildJsonObject {
            put("disabled_providers", JsonArray(merged.map { JsonPrimitive(it) }))
        }.toString()
        patch("/global/config", body)
        return true
    }

    suspend fun saveCustom(input: CustomProviderSaveDto): Boolean {
        val id = input.id.trim()
        val env = input.envVar?.trim()?.takeIf { it.isNotBlank() }
        val models = input.models.associate { model ->
            model.id to buildJsonObject {
                put("id", model.id)
                put("name", model.name.ifBlank { model.id })
                put("capabilities", buildJsonObject { put("reasoning", model.reasoning) })
            }
        }
        val provider = buildJsonObject {
            put("name", input.name.trim().ifBlank { id })
            put("npm", "@ai-sdk/openai-compatible")
            put("options", buildJsonObject { put("baseURL", input.baseUrl.trim()) })
            if (env != null) put("env", JsonArray(listOf(JsonPrimitive(env))))
            if (input.headers.isNotEmpty()) put("headers", buildJsonObject { input.headers.forEach { (k, v) -> put(k, v) } })
            if (models.isNotEmpty()) put("models", JsonObject(models))
        }
        val body = buildJsonObject {
            put("provider", buildJsonObject { put(id, provider) })
        }.toString()
        patchScoped(input.directory, body)
        return true
    }

    suspend fun deleteCustom(id: String): Boolean {
        val body = buildJsonObject {
            put("provider", buildJsonObject { put(id, JsonNull) })
        }.toString()
        patchScoped(null, body)
        return true
    }

    suspend fun fetch(input: CustomModelFetchDto): CustomModelFetchResultDto = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(input.baseUrl.trim().removeSuffix("/") + "/models"))
            .timeout(Duration.ofSeconds(FETCH_TIMEOUT_SECONDS))
            .GET()
        input.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        input.headers.forEach { (k, v) -> builder.header(k, v) }
        val response = fetchClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode()}: ${response.body().take(200)}")
        }
        val ids = json.parseToJsonElement(response.body()).jsonObject["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.let { runCatching { it.content }.getOrNull() } }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.sorted()
            ?: emptyList()
        CustomModelFetchResultDto(models = ids)
    }

    suspend fun dispose(): Boolean {
        post("/global/dispose")
        return true
    }

    private suspend fun patchScoped(directory: String?, body: String) {
        if (directory != null && directory.isNotBlank()) {
            patch("/config?directory=${enc(directory)}", body)
        } else {
            patch("/global/config", body)
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}

