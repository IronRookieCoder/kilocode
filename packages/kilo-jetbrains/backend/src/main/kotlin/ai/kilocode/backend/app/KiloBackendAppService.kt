package ai.kilocode.backend.app

import ai.kilocode.backend.migration.KiloBackendLegacyMigrationStoreService
import ai.kilocode.backend.migration.LegacyMigrationDetection
import ai.kilocode.backend.migration.LegacyMigrationStatus
import ai.kilocode.backend.telemetry.KiloBackendTelemetry
import ai.kilocode.backend.workspace.KiloBackendWorkspaceManager
import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.ConnectionProvider
import ai.kilocode.connection.ConnectionState
import ai.kilocode.connection.Transport
import ai.kilocode.connection.TransportFactory
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.ConfigDto
import ai.kilocode.rpc.dto.ConfigPatchDto
import ai.kilocode.rpc.dto.ConfigWarningDto
import ai.kilocode.rpc.dto.DeviceAuthDto
import ai.kilocode.rpc.dto.HealthDto
import ai.kilocode.rpc.dto.ProfileDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Application service that owns the backend connection lifecycle and all
 * app-level state: profile, config, warnings, session/chat managers, and
 * legacy migration gating.
 *
 * The transport comes from a [ConnectionProvider] (implemented by the
 * cs-cloud connector module); this class never touches process or HTTP
 * details directly.
 */
@Service(Service.Level.APP)
class KiloBackendAppService private constructor(
    private val cs: CoroutineScope,
    private val provider: ConnectionProvider,
    private val log: KiloLog,
    private val loadTimeoutMs: Long = APP_LOAD_TIMEOUT_MS,
) : Disposable {

    /** IntelliJ service injection entry point. */
    constructor(cs: CoroutineScope) : this(
        cs,
        UnconfiguredConnectionProvider(),
        KiloLog.create(KiloBackendAppService::class.java),
        APP_LOAD_TIMEOUT_MS,
    )

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val APP_LOAD_TIMEOUT_MS = 30_000L
        private const val READY_TIMEOUT_MS = 120_000L
        private const val LOGIN_CODE_REGEX = "code: (\\S+)"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val partialJson = Json { explicitNulls = false }

        fun appService(): KiloBackendAppService = service()

        /** Test factory — no IntelliJ service lookup. */
        internal fun create(
            cs: CoroutineScope,
            provider: ConnectionProvider,
            log: KiloLog,
            loadTimeoutMs: Long = APP_LOAD_TIMEOUT_MS,
        ) = KiloBackendAppService(cs, provider, log, loadTimeoutMs)
    }

    private val mutex = Mutex()
    private var watcher: Job? = null
    private var eventWatcher: Job? = null
    private var loader: Job? = null
    private var closed = false
    private var migrationOffered = false
    private var migrationSuppressed = false
    private var migrationForceRequested = false
    private val loadLock = Any()
    private val rev = java.util.concurrent.atomic.AtomicLong()

    private val _appState = MutableStateFlow<KiloAppState>(KiloAppState.Disconnected)
    val appState: StateFlow<KiloAppState> = _appState.asStateFlow()

    val events: SharedFlow<BackendEvent> get() = provider.events

    val sessions = KiloBackendSessionManager(cs, log)
    val chat = KiloBackendChatManager(cs, log)
    val activity = KiloBackendActivityManager(cs, log)
    val models = KiloBackendModelStateManager(log)
    val workspaces = KiloBackendWorkspaceManager(cs, sessions, log)

    private var profile: ProfileDto? = null
    var config: ConfigDto? = null
    private var warnings: List<ConfigWarningDto> = emptyList()

    // ------ transports ------

    fun transportFactory(): TransportFactory? = provider.transportFactory()

    fun transport(): Transport =
        provider.transportFactory()?.create() ?: throw IllegalStateException("Backend not connected")

    // ------ connection lifecycle ------

    suspend fun connect() {
        mutex.withLock {
            if (closed) return
            ensureWatcher()
            _appState.value = if (preservesMigration(_appState.value, ConnectionState.Connecting)) _appState.value else KiloAppState.Connecting
            provider.connect()
        }
    }

    suspend fun restart() {
        mutex.withLock {
            if (closed) return
            log.info("Restarting backend connection")
            clear()
            provider.restart()
        }
    }

    suspend fun reinstall() {
        log.info("Reinstall requested — restarting connection")
        restart()
    }

    suspend fun retry() {
        if (provider.transportFactory() != null) restart() else connect()
    }

    suspend fun health(): HealthDto = try {
        json.decodeFromString(HealthDto.serializer(), transport().use { it.call("GET", "/global/health") })
    } catch (e: Exception) {
        log.debug { "health check failed: ${e.message}" }
        HealthDto(healthy = false, version = "unknown")
    }

    fun requireReady(): AppData {
        val state = _appState.value
        if (state is KiloAppState.Ready) return state.data
        if (state is KiloAppState.Error) throw IllegalStateException(state.message)
        throw IllegalStateException("Application is not ready (state=${state::class.simpleName})")
    }

    suspend fun awaitReady(): AppData? {
        val state = withTimeoutOrNull(READY_TIMEOUT_MS) {
            appState.first { it is KiloAppState.Ready || it is KiloAppState.Error }
        } ?: return null
        return (state as? KiloAppState.Ready)?.data
    }

    // ------ watcher ------

    private fun ensureWatcher() {
        synchronized(loadLock) {
            if (watcher?.isActive == true) return
            watcher = cs.launch {
                provider.state.collect { state ->
                    val current = _appState.value
                    if (preservesMigration(current, state)) return@collect
                    when (state) {
                        ConnectionState.Connecting -> _appState.value = KiloAppState.Connecting
                        is ConnectionState.Connected -> load()
                        is ConnectionState.Error -> _appState.value = KiloAppState.Error(
                            state.message,
                            listOf(LoadError("connection", null, state.details ?: state.message)),
                        )
                        ConnectionState.Disconnected -> _appState.value = KiloAppState.Disconnected
                    }
                }
            }
        }
    }

    // ------ load cycle ------

    private fun load(): Job? = synchronized(loadLock) {
        if (closed) return null
        loader?.cancel()
        val job = cs.launch {
            val detection = detectMigration()
            if (detection != null) {
                _appState.value = KiloAppState.MigrationRequired(detection)
                log.warn("Legacy data detected — import required before startup")
                return@launch
            }

            val factory = provider.transportFactory()
            if (factory == null) {
                _appState.value = KiloAppState.Error("Not connected")
                return@launch
            }

            _appState.value = KiloAppState.Loading(LoadProgress())

            suspend fun <T> fetch(name: String, block: suspend () -> T): FetchResult<T> {
                var attempt = 0
                while (true) {
                    val result = try {
                        FetchResult.Loaded(block())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ai.kilocode.connection.TransportException) {
                        FetchResult.HttpError(name, e.status, e.body ?: e.message)
                    } catch (e: Exception) {
                        FetchResult.HttpError(name, null, e.message)
                    }
                    if (result is FetchResult.HttpError) {
                        attempt++
                        if (attempt < MAX_RETRIES) {
                            log.warn("fetch $name failed (attempt $attempt): ${result.status} ${result.detail}")
                            delay(RETRY_DELAY_MS)
                        } else {
                            return result
                        }
                    } else {
                        return result
                    }
                }
            }

            val (configResult, profileResult, warningsResult) = coroutineScope {
                val configJob = async { fetch("config") { fetchConfig() } }
                val profileJob = async { fetch("profile") { fetchProfile() } }
                val warningsJob = async { fetch("warnings") { fetchWarnings() } }
                awaitAll(configJob, profileJob, warningsJob)
                @Suppress("UNCHECKED_CAST")
                Triple(
                    configJob.await() as FetchResult<ConfigDto>,
                    profileJob.await() as FetchResult<ProfileDto?>,
                    warningsJob.await() as FetchResult<List<ConfigWarningDto>>,
                )
            }

            val errors = listOf(configResult, profileResult, warningsResult)
                .filterIsInstance<FetchResult.HttpError<*>>()
                .map { LoadError(it.resource, it.status, it.detail) }

            if (configResult is FetchResult.HttpError) {
                _appState.value = KiloAppState.Error("Failed to load config", errors)
                return@launch
            }

            config = (configResult as FetchResult.Loaded<ConfigDto>).value
            profile = (profileResult as? FetchResult.Loaded)?.value
            warnings = (warningsResult as? FetchResult.Loaded)?.value ?: emptyList()

            val factoryNow = provider.transportFactory()
            if (factoryNow != null) {
                models.start(factoryNow.create())
                sessions.start(factoryNow.create(), events)
                chat.start(factoryNow.create(), events)
                workspaces.start(factoryNow, events)
            }
            activity.start(sessions.statuses, sessions::sessionDirectory, chat.events)
            startWatchingGlobalSseEvents()

            setTelemetry(true)
            captureBackend()
            captureLoad(errors)

            setAppReady(
                AppData(
                    profile = profile,
                    config = config ?: ConfigDto(),
                    warnings = warnings,
                ),
            )
        }
        loader = job
        job
    }

    // ------ global SSE events ------

    private fun startWatchingGlobalSseEvents() {
        if (eventWatcher?.isActive == true) return
        eventWatcher = cs.launch {
            events.collect { event ->
                when (event.type) {
                    "global.config.updated" -> {
                        config = try {
                            fetchConfig()
                        } catch (e: Exception) {
                            config
                        }
                        val state = _appState.value
                        if (state is KiloAppState.Ready) {
                            _appState.value = KiloAppState.Ready(
                                state.data.copy(config = config ?: state.data.config),
                                state.rev + 1,
                            )
                        }
                        log.debug { "config updated via SSE" }
                    }
                    "global.disposed", "server.instance.disposed" -> {
                        log.info("Backend disposed (${event.type}) — restarting")
                        cs.launch { restart() }
                    }
                }
            }
        }
    }

    // ------ config / profile fetches ------

    private suspend fun fetchConfig(): ConfigDto =
        json.decodeFromString(ConfigDto.serializer(), transport().use { it.call("GET", "/global/config") })

    private suspend fun fetchWarnings(): List<ConfigWarningDto> =
        json.decodeFromString(
            ListSerializer(ConfigWarningDto.serializer()),
            transport().use { it.call("GET", "/global/config/warnings") },
        )

    private suspend fun fetchProfile(): ProfileDto? {
        return try {
            json.decodeFromString(ProfileDto.serializer(), transport().use { it.call("GET", "/kilo/profile") })
        } catch (e: ai.kilocode.connection.TransportException) {
            if (e.status == 401 || e.status == 400) null else throw e
        }
    }

    suspend fun updateConfig(patch: ConfigPatchDto): KiloAppState {
        val body = partialJson.encodeToString(ConfigPatchDto.serializer(), patch)
        try {
            transport().use { it.call("PATCH", "/global/config", body) }
            log.info("Config updated: keys=${patch.values.keys.toList()}, agents=${patch.agents?.size ?: 0}")
            refreshConfigState()
        } catch (e: Exception) {
            log.warn("config update failed: ${e.message}")
        }
        return _appState.value
    }

    fun refreshConfigState() {
        val current = config
        cs.launch {
            try {
                config = fetchConfig()
            } catch (e: Exception) {
                log.warn("config refresh failed: ${e.message}")
                config = current
            }
            val state = _appState.value
            val cfg = config
            if (state is KiloAppState.Ready && cfg != null) {
                _appState.value = KiloAppState.Ready(state.data.copy(config = cfg), state.rev + 1)
            }
        }
    }

    // ------ login / profile ------

    suspend fun startLogin(directory: String?): DeviceAuthDto {
        val route = "/provider/oauth/authorize?providerID=kilo" + (directory?.let { "&directory=" + encode(it) } ?: "")
        val raw = transport().use { it.call("POST", route, buildJsonObject { put("method", 0) }.toString()) }
        val obj = json.parseToJsonElement(raw).jsonObject
        val url = obj["url"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Login response missing url")
        val instructions = obj["instructions"]?.jsonPrimitive?.content.orEmpty()
        val code = Regex(LOGIN_CODE_REGEX).find(instructions)?.groupValues?.get(1)
            ?: throw IllegalStateException("Login instructions missing code")
        return DeviceAuthDto(
            code = code,
            verificationUrl = url,
            expiresIn = 900,
        )
    }

    suspend fun completeLogin(directory: String?): ProfileDto? {
        val route = "/provider/oauth/callback?providerID=kilo" + (directory?.let { "&directory=" + encode(it) } ?: "")
        transport().use { it.call("POST", route, buildJsonObject { put("method", 0) }.toString()) }
        return refreshProfile()
    }

    suspend fun logout(): Boolean {
        val raw = try {
            transport().use { it.call("POST", "/auth/remove?providerID=kilo") }
        } catch (e: Exception) {
            log.warn("logout failed: ${e.message}")
            return false
        }
        val ok = raw.trim().toBooleanStrictOrNull() ?: false
        if (ok) {
            profile = null
            val state = _appState.value
            if (state is KiloAppState.Ready) {
                _appState.value = KiloAppState.Ready(state.data.copy(profile = null), state.rev + 1)
            }
        }
        return ok
    }

    suspend fun setOrganization(organizationId: String?): ProfileDto? {
        val body = buildJsonObject {
            if (organizationId != null) put("organizationId", organizationId)
        }.toString()
        try {
            transport().use { it.call("POST", "/kilo/organization", body) }
            return refreshProfile()
        } catch (e: Exception) {
            log.warn("set organization failed: ${e.message}")
            return null
        }
    }

    suspend fun refreshProfile(): ProfileDto? {
        profile = try {
            fetchProfile()
        } catch (e: Exception) {
            log.warn("profile refresh failed: ${e.message}")
            null
        }
        val state = _appState.value
        if (state is KiloAppState.Ready) {
            _appState.value = KiloAppState.Ready(state.data.copy(profile = profile), state.rev + 1)
        }
        return profile
    }

    // ------ migration ------

    private suspend fun detectMigration(): LegacyMigrationDetection? = withContext(Dispatchers.IO) {
        try {
            if (provider.transportFactory() == null) {
                log.info("Migration check: skipped because transport is not connected")
                return@withContext null
            }
            log.info("Migration check: started")
            // Status is only consulted when the in-memory flags do not already block the offer,
            // preserving the original short-circuit order.
            val status = if (migrationSuppressed || migrationOffered) null
            else KiloBackendLegacyMigrationStoreService.status(log)
            val gate = migrationGate(migrationSuppressed, migrationOffered, status)
            if (gate != MigrationGate.Proceed) {
                log.info("Migration check: skipped gate=$gate status=$status")
                return@withContext null
            }
            val source = KiloBackendLegacyMigrationStoreService.resolveSource(log, includeFile = migrationForceRequested)
            val store = source.store
            val detection = KiloBackendMigrationManager { provider.transportFactory()!!.create() }.detect(store)
            log.info("Migration check: completed hasData=${detection.hasData} ${migrationSummary(detection)}")
            if (!detection.hasData) return@withContext null
            migrationOffered = true
            detection
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Migration check failed: ${e.message}", e)
            null
        }
    }

    private fun migrationSummary(detection: LegacyMigrationDetection): String {
        val providers = detection.providers.count { it.supported }
        val unsupported = detection.providers.size - providers
        return "providers=$providers unsupportedProviders=$unsupported mcp=${detection.mcpServers.size} modes=${detection.customModes.size} sessions=${detection.sessions.size} model=${detection.defaultModel != null} settings=${detection.settings != null}"
    }

    internal suspend fun resumeAfterMigration() {
        mutex.withLock {
            if (_appState.value !is KiloAppState.MigrationRequired) return
            migrationSuppressed = true
            load()
            migrationForceRequested = false
        }
    }

    internal fun resetMigrationOfferForRerun() {
        migrationOffered = false
        migrationSuppressed = false
        migrationForceRequested = true
    }

    internal fun forceMigrationRequested(): Boolean = migrationForceRequested

    // ------ telemetry ------

    private fun captureBackend() {
        val factory = provider.transportFactory() ?: return
        cs.launch {
            runCatching {
                service<KiloBackendTelemetry>().capture(factory.create(), "backend_started", emptyMap())
            }.onFailure { log.info("Skipping backend telemetry: ${it.message}") }
        }
    }

    private fun captureLoad(errors: List<LoadError>) {
        val factory = provider.transportFactory() ?: return
        val props = mapOf(
            "success" to errors.isEmpty().toString(),
            "errors" to errors.size.toString(),
        )
        cs.launch {
            runCatching {
                service<KiloBackendTelemetry>().capture(factory.create(), "app_loaded", props)
            }.onFailure { log.info("Skipping load telemetry: ${it.message}") }
        }
    }

    private fun setTelemetry(enabled: Boolean) {
        val factory = provider.transportFactory() ?: return
        cs.launch {
            runCatching {
                service<KiloBackendTelemetry>().setEnabled(factory.create(), enabled)
            }.onFailure { log.info("Skipping telemetry toggle: ${it.message}") }
        }
    }

    // ------ state helpers ------

    private fun setAppReady(data: AppData) {
        _appState.value = KiloAppState.Ready(data, rev.incrementAndGet())
        log.info("App ready (rev=${rev.get()})")
    }

    private fun clear() {
        loader?.cancel()
        loader = null
        eventWatcher?.cancel()
        eventWatcher = null
        _appState.value = KiloAppState.Disconnected
        config = null
        profile = null
        warnings = emptyList()
        sessions.stop()
        chat.stop()
        activity.stop()
        models.stop()
        workspaces.stop()
    }

    // ------ shutdown ------

    @Synchronized
    fun shutdown(fast: Boolean = false) {
        if (closed) return
        closed = true
        watcher?.cancel()
        clear()
        provider.dispose()
        log.info("Backend app service shut down (fast=$fast)")
    }

    override fun dispose() {
        shutdown(fast = true)
    }

    fun shutdownForAppClose() {
        shutdown(fast = true)
    }

    fun shutdownForUnload() {
        shutdown(fast = false)
    }

    // ------ helpers ------

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** Result of a retried fetch. */
    internal sealed class FetchResult<out T> {
        data class Loaded<out T>(val value: T) : FetchResult<T>()
        data class HttpError<out T>(
            val resource: String,
            val status: Int?,
            val detail: String?,
        ) : FetchResult<T>()
    }
}

/** Why a startup migration offer is or is not made. */
internal enum class MigrationGate { Proceed, Suppressed, AlreadyOffered, StatusSet }

/**
 * Pure startup-gating decision for the migration offer. Suppression (dismissed this startup)
 * takes priority, then a prior offer this startup, then a persisted status.
 */
internal fun migrationGate(
    suppressed: Boolean,
    offered: Boolean,
    status: LegacyMigrationStatus?,
): MigrationGate = when {
    suppressed -> MigrationGate.Suppressed
    offered -> MigrationGate.AlreadyOffered
    status != null -> MigrationGate.StatusSet
    else -> MigrationGate.Proceed
}

/**
 * Whether a connection-state transition must be ignored to keep the migration wizard up.
 *
 * A pending migration is a higher-level gate that must survive transient connection churn.
 * Applying Connecting/Connected/Error while [KiloAppState.MigrationRequired] would flip the
 * app out of the wizard; only the user resolving migration (skip/later/finish) leaves the
 * state, via resumeAfterMigration()/load().
 */
internal fun preservesMigration(appState: KiloAppState, next: ConnectionState): Boolean =
    appState is KiloAppState.MigrationRequired &&
        (next == ConnectionState.Connecting || next is ConnectionState.Connected || next is ConnectionState.Error)
