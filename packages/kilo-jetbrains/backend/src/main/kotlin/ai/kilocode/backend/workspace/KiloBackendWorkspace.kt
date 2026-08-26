package ai.kilocode.backend.workspace

import ai.kilocode.backend.app.KiloBackendSessionManager
import ai.kilocode.backend.app.LoadError
import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.Transport
import ai.kilocode.connection.TransportException
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.SessionDto
import ai.kilocode.rpc.dto.SessionListDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Per-directory workspace data loader: providers, agents, commands, and skills.
 *
 * Fetches run in parallel through the injected [Transport]; payloads are JSON
 * documents shaped by the workspace data classes in [KiloWorkspaceState].
 */
class KiloBackendWorkspace(
    val directory: String,
    private val cs: CoroutineScope,
    private val transport: Transport,
    events: SharedFlow<BackendEvent>,
    private val sessions: KiloBackendSessionManager,
    private val log: KiloLog,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow<KiloWorkspaceState>(KiloWorkspaceState.Pending)
    val state: StateFlow<KiloWorkspaceState> = _state.asStateFlow()

    private var loader: Job? = null
    private var watcher: Job? = null

    init {
        watcher = cs.launch {
            events.collect { event ->
                when (event.type) {
                    "global.disposed", "server.instance.disposed" -> reload()
                }
            }
        }
        load()
    }

    fun reload() {
        _state.value = KiloWorkspaceState.Pending
        load()
    }

    private fun load() {
        if (loader?.isActive == true) return
        loader = cs.launch {
            _state.value = KiloWorkspaceState.Loading(KiloWorkspaceLoadProgress())

            suspend fun <T> fetch(name: String, block: suspend () -> T): FetchResult<T> = try {
                FetchResult.Success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                FetchResult.HttpError(name, e.status, e.body ?: e.message)
            } catch (e: Exception) {
                FetchResult.HttpError(name, null, e.message)
            }

            fun progress(current: KiloWorkspaceState, next: KiloWorkspaceLoadProgress) {
                if (current is KiloWorkspaceState.Loading) _state.value = KiloWorkspaceState.Loading(next)
            }

            val (providersRes, agentsRes, commandsRes, skillsRes) = kotlinx.coroutines.coroutineScope {
                val providersJob = async { fetch("providers") { fetchProviders() } }
                val agentsJob = async { fetch("agents") { fetchAgents() } }
                val commandsJob = async { fetch("commands") { fetchCommands() } }
                val skillsJob = async { fetch("skills") { fetchSkills() } }
                val results = awaitAll(providersJob, agentsJob, commandsJob, skillsJob)
                @Suppress("UNCHECKED_CAST")
                KiloWorkspaceResults(
                    results[0] as FetchResult<ProviderData>,
                    results[1] as FetchResult<AgentData>,
                    results[2] as FetchResult<List<CommandInfo>>,
                    results[3] as FetchResult<List<SkillInfo>>,
                )
            }

            val failures = listOf(providersRes, agentsRes, commandsRes, skillsRes)
                .filterIsInstance<FetchResult.HttpError<*>>()
                .map { LoadError(it.resource, it.status, it.detail) }

            if (failures.isNotEmpty() && providersRes is FetchResult.HttpError) {
                _state.value = KiloWorkspaceState.Error(
                    "Failed to load providers",
                    failures,
                )
                return@launch
            }

            val providers = (providersRes as FetchResult.Success).value
            val agents = (agentsRes as? FetchResult.Success)?.value ?: AgentData(emptyList(), emptyList(), "code")
            val commands = (commandsRes as? FetchResult.Success)?.value ?: emptyList()
            val skills = (skillsRes as? FetchResult.Success)?.value ?: emptyList()

            _state.value = KiloWorkspaceState.Ready(
                providers = providers,
                agents = agents,
                commands = commands,
                skills = skills,
            )
            log.info("Workspace loaded: dir=" + directory + " providers=" + providers.providers.size)
        }
    }

    // ------ fetches ------

    private suspend fun fetchProviders(): ProviderData {
        val raw = transport.call("GET", "/provider?directory=" + encode(directory))
        return ProviderWire.parse(raw)
    }

    private suspend fun fetchAgents(): AgentData {
        val raw = transport.call("GET", "/app/agents?directory=" + encode(directory))
        val all = json.decodeFromString(ListSerializer(AgentInfo.serializer()), raw)
        val visible = all.filter { it.mode != "subagent" && it.hidden != true }
        return AgentData(
            agents = visible,
            all = all,
            default = visible.firstOrNull()?.name ?: "code",
        )
    }

    private suspend fun fetchCommands(): List<CommandInfo> {
        val raw = transport.call("GET", "/command?directory=" + encode(directory))
        return json.decodeFromString(ListSerializer(CommandInfo.serializer()), raw)
    }

    private suspend fun fetchSkills(): List<SkillInfo> {
        val raw = transport.call("GET", "/app/skills?directory=" + encode(directory))
        return json.decodeFromString(ListSerializer(SkillInfo.serializer()), raw)
    }

    // ------ session helpers (delegate to session manager) ------

    suspend fun sessions(): SessionListDto = sessions.list(directory)

    suspend fun createSession(): SessionDto = sessions.create(directory)

    suspend fun deleteSession(id: String) = sessions.delete(id, directory)

    // ------ lifecycle ------

    fun stop() {
        watcher?.cancel()
        watcher = null
        loader?.cancel()
        loader = null
        transport.close()
        _state.value = KiloWorkspaceState.Pending
    }

    // ------ helpers ------

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    internal sealed class FetchResult<out T> {
        data class Success<out T>(val value: T) : FetchResult<T>()
        data class HttpError<out T>(
            val resource: String,
            val status: Int?,
            val detail: String?,
        ) : FetchResult<T>()
    }

    private data class KiloWorkspaceResults(
        val providers: FetchResult<ProviderData>,
        val agents: FetchResult<AgentData>,
        val commands: FetchResult<List<CommandInfo>>,
        val skills: FetchResult<List<SkillInfo>>,
    )
}
