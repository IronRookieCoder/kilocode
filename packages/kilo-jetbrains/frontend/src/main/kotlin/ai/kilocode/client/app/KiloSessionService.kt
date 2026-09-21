@file:Suppress("UnstableApiUsage")

package ai.kilocode.client.app

import ai.kilocode.log.ChatLogSummary
import ai.kilocode.rpc.KiloSessionRpcApi
import ai.kilocode.client.session.SessionActivityKind
import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.CloudSessionListDto
import ai.kilocode.rpc.dto.CommitMessageRequestDto
import ai.kilocode.rpc.dto.CommitMessageResultDto
import ai.kilocode.rpc.dto.ConfigUpdateDto
import ai.kilocode.rpc.dto.DiffFileDto
import ai.kilocode.rpc.dto.MessageWithPartsDto
import ai.kilocode.rpc.dto.ModelSelectionDto
import ai.kilocode.rpc.dto.PermissionAlwaysRulesDto
import ai.kilocode.rpc.dto.PermissionReplyDto
import ai.kilocode.rpc.dto.PermissionRequestDto
import ai.kilocode.rpc.dto.PartDto
import ai.kilocode.rpc.dto.PromptDto
import ai.kilocode.rpc.dto.QuestionReplyDto
import ai.kilocode.rpc.dto.QuestionRequestDto
import ai.kilocode.rpc.dto.SessionActivityDto
import ai.kilocode.rpc.dto.SessionDto
import ai.kilocode.rpc.dto.SessionListDto
import ai.kilocode.rpc.dto.SessionStatusDto
import com.intellij.openapi.components.Service
import ai.kilocode.log.KiloLog
import com.intellij.openapi.project.Project
import ai.kilocode.stability.Operations
import ai.kilocode.stability.StabilityService
import ai.kilocode.stability.rpc
import com.intellij.openapi.components.service
import fleet.rpc.client.durable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Project-level frontend service for session management.
 *
 * Stateless with respect to "active session" — callers pass explicit
 * session IDs. [ai.kilocode.client.session.controller.SessionController] owns the
 * active session concept.
 */
@Service(Service.Level.PROJECT)
class KiloSessionService internal constructor(
    private val project: Project,
    private val cs: CoroutineScope,
    private val rpc: KiloSessionRpcApi?,
    private val log: KiloLog = LOG,
    // 稳定性采集入口（C2/M19）：生产经平台构造器注入；null=采集不可用，业务照常（B2注入模式）。
    private val operations: Operations? = null,
) {
    /** Platform constructor — resolves RPC from the service container. */
    constructor(project: Project, cs: CoroutineScope) : this(
        project,
        cs,
        null,
        LOG,
        service<StabilityService>().operations,
    )

    companion object {
        private val LOG = KiloLog.create(KiloSessionService::class.java)

        /** M19方法组受控词表（metrics"前后端通信"维度）：本服务全部RPC都是会话组。 */
        private const val GROUP_SESSION = "session"
    }

    // Reflects the sessions from the most recent tracking [list]/[renameSession] call, which is
    // scoped to a single directory. It is NOT a per-workspace source of truth: a caller listing a
    // different directory (e.g. an Agent Manager worktree tab) overwrites it. Directory-scoped
    // callers must consume the return value of [list]/[sessionsFor], never this flow.
    private val _sessions = MutableStateFlow<List<SessionDto>>(emptyList())
    val sessions: StateFlow<List<SessionDto>> = _sessions.asStateFlow()

    /** Live session status map from SSE events. */
    val statuses: StateFlow<Map<String, SessionStatusDto>> =
        stream { statuses() }.stateIn(cs, SharingStarted.Eagerly, emptyMap())

    /** Live session activity map from backend global events. */
    val activity: StateFlow<Map<String, SessionActivityDto>> =
        stream { activity() }.stateIn(cs, SharingStarted.Eagerly, emptyMap())

    // ------ RPC helpers ------

    /**
     * M19（C2）：wrapper置于durable{}内每次实际调用处——durable重连重试重新执行lambda时
     * 各自形成独立attempt；直连RPC（split模式注入api）同样按次观测。长寿命Flow订阅
     * （[stream]/[events]/state收集）不观测，绝不把整个订阅时长当RPC。
     */
    private suspend fun <T> call(group: String, block: suspend KiloSessionRpcApi.() -> T): T {
        val api = rpc
        return if (api != null) {
            observed(group) { block(api) }
        } else {
            durable { observed(group) { block(KiloSessionRpcApi.getInstance()) } }
        }
    }

    /** operations未注入时零开销直连；注入后每次实际调用一个rpc操作（成功仅metrics出口）。 */
    private suspend fun <T> observed(group: String, block: suspend () -> T): T {
        val observer = operations ?: return block()
        return observer.rpc(group) { block() }
    }

    private fun <T> stream(block: suspend KiloSessionRpcApi.() -> Flow<T>): Flow<T> = flow {
        val api = rpc
        if (api != null) block(api).collect { emit(it) }
        else durable { block(KiloSessionRpcApi.getInstance()).collect { emit(it) } }
    }

    // ------ Session CRUD ------

    /** Refresh the session list from the server. */
    fun refresh(dir: String) {
        cs.launch {
            try {
                list(dir)
            } catch (e: Exception) {
                log.warn("kind=session-list dir=${ChatLogSummary.dir(dir)} failed message=${e.message}", e)
            }
        }
    }

    internal fun activitySnapshot(): Map<String, SessionActivityKind> =
        statuses.value
            .filterValues { it.type == "busy" }
            .mapValues { SessionActivityKind.RUNNING }

    suspend fun list(dir: String): SessionListDto {
        val result = call(GROUP_SESSION) { list(dir) }
        _sessions.value = result.sessions
        return result
    }

    /**
     * List sessions for [dir] without touching the shared [sessions] flow. Use this for
     * directory-scoped views (e.g. Agent Manager worktree tabs) that maintain their own model, so a
     * background refresh does not clobber the primary workspace's [sessions] snapshot.
     */
    suspend fun sessionsFor(dir: String): SessionListDto = call(GROUP_SESSION) { list(dir) }

    /** Load recent sessions for the current worktree family. */
    suspend fun recent(dir: String, limit: Int): List<SessionDto> =
        call(GROUP_SESSION) { recent(dir, limit) }.sessions

    /** Get a single session. */
    suspend fun get(id: String, dir: String): SessionDto =
        call(GROUP_SESSION) { get(id, dir) }

    /** Create a new session. Caller awaits the result. */
    suspend fun create(dir: String): SessionDto {
        log.info("kind=session create=true dir=${ChatLogSummary.dir(dir)}")
        val session = call(GROUP_SESSION) { create(dir) }
        log.info("${ChatLogSummary.sid(session.id)} kind=session create=true ok=true dir=${ChatLogSummary.dir(dir)}")
        refresh(dir)
        return session
    }

    /** Delete a session. */
    fun delete(id: String, dir: String) {
        cs.launch {
            try {
                deleteSession(id, dir)
            } catch (e: Exception) {
                log.warn("${ChatLogSummary.sid(id)} kind=session delete=true dir=${ChatLogSummary.dir(dir)} failed message=${e.message}", e)
            }
        }
    }

    suspend fun deleteSession(id: String, dir: String) {
        log.info("${ChatLogSummary.sid(id)} kind=session delete=true dir=${ChatLogSummary.dir(dir)}")
        call(GROUP_SESSION) { delete(id, dir) }
        log.info("${ChatLogSummary.sid(id)} kind=session delete=true ok=true dir=${ChatLogSummary.dir(dir)}")
        list(dir)
    }

    suspend fun renameSession(id: String, dir: String, newTitle: String): ai.kilocode.rpc.dto.SessionDto {
        val session = call(GROUP_SESSION) { rename(id, dir, newTitle) }
        _sessions.value = _sessions.value.map { if (it.id == id) session else it }
        return session
    }

    suspend fun cloudSessions(dir: String, cursor: String?, limit: Int, gitUrl: String?): CloudSessionListDto =
        call(GROUP_SESSION) { cloudSessions(dir, cursor, limit, gitUrl) }

    suspend fun importCloudSession(id: String, dir: String): SessionDto =
        call(GROUP_SESSION) { importCloudSession(id, dir) }

    /** Register a worktree directory override for a session. */
    fun setDirectory(id: String, dir: String) {
        cs.launch {
            try {
                call(GROUP_SESSION) { setDirectory(id, dir) }
            } catch (e: Exception) {
                log.warn("${ChatLogSummary.sid(id)} kind=session setDirectory=true dir=${ChatLogSummary.dir(dir)} failed message=${e.message}", e)
            }
        }
    }

    // ------ Chat ops (explicit session ID) ------

    suspend fun enhancePrompt(dir: String, text: String): String =
        call(GROUP_SESSION) { enhancePrompt(dir, text) }

    suspend fun generateCommitMessage(input: CommitMessageRequestDto): CommitMessageResultDto =
        call(GROUP_SESSION) { generateCommitMessage(input) }

    /** Send a prompt to a session. */
    suspend fun prompt(id: String, dir: String, dto: PromptDto) {
        val meta = if (log.isDebugEnabled) {
            "${ChatLogSummary.dir(dir)} ${ChatLogSummary.prompt(dto)}"
        } else {
            "kind=prompt parts=${dto.parts.size}"
        }
        log.info("${ChatLogSummary.sid(id)} $meta")
        call(GROUP_SESSION) { prompt(id, dir, dto) }
        log.info("${ChatLogSummary.sid(id)} kind=prompt ok=true")
    }

    suspend fun command(id: String, dir: String, command: String, args: String, dto: PromptDto) {
        log.info("${ChatLogSummary.sid(id)} kind=command command=$command parts=${dto.parts.size}")
        call(GROUP_SESSION) { command(id, dir, command, args, dto) }
        log.info("${ChatLogSummary.sid(id)} kind=command ok=true")
    }

    /** Abort ongoing processing for a session. */
    suspend fun abort(id: String, dir: String) {
        log.info("${ChatLogSummary.sid(id)} kind=abort ${ChatLogSummary.dir(dir)}")
        call(GROUP_SESSION) { abort(id, dir) }
        log.info("${ChatLogSummary.sid(id)} kind=abort ok=true")
    }

    /** Summarize/compact a session. */
    suspend fun compact(id: String, dir: String, model: ModelSelectionDto) {
        call(GROUP_SESSION) { compact(id, dir, model) }
    }

    suspend fun revert(id: String, dir: String, message: String, part: String?) {
        log.info(
            "${ChatLogSummary.sid(id)} kind=revert ${ChatLogSummary.dir(dir)} " +
                "message=$message part=${part ?: "none"}",
        )
        call(GROUP_SESSION) { revert(id, dir, message, part) }
        log.info("${ChatLogSummary.sid(id)} kind=revert ok=true")
    }

    suspend fun deleteMessage(id: String, dir: String, message: String): Boolean =
        call(GROUP_SESSION) { deleteMessage(id, dir, message) }

    suspend fun unrevert(id: String, dir: String) {
        call(GROUP_SESSION) { unrevert(id, dir) }
    }

    /** Load message history for a session. */
    suspend fun messages(id: String, dir: String): List<MessageWithPartsDto> =
        call(GROUP_SESSION) { messages(id, dir) }
            .also { log.debug { "${ChatLogSummary.sid(id)} ${ChatLogSummary.history(it)} ${ChatLogSummary.dir(dir)}" } }

    // Errors propagate so the diff editor can distinguish a real failure (retry link) from "no changes".
    suspend fun diff(id: String, dir: String): List<DiffFileDto> =
        call(GROUP_SESSION) { diff(id, dir) }

    suspend fun diffSides(sessionId: String?, dir: String, file: DiffFileDto, messageId: String?): DiffFileDto? =
        call(GROUP_SESSION) { diffSides(sessionId, dir, file, messageId) }

    suspend fun attachmentPart(id: String, dir: String, message: String, part: String, key: String?): PartDto? =
        call(GROUP_SESSION) { attachmentPart(id, dir, message, part, key) }

    /** Subscribe to streaming chat events for a session. */
    fun events(id: String, dir: String): Flow<ChatEventDto> {
        val api = rpc
        val events = if (api != null) flow {
            api.events(id, dir).collect {
                log.debug { ChatLogSummary.event(it) }
                ChatLogSummary.error(it)?.let { msg -> log.warn("${ChatLogSummary.sid(id)} route=client-events $msg") }
                emit(it)
            }
        } else flow {
            durable {
                KiloSessionRpcApi.getInstance().events(id, dir).collect {
                    log.debug { ChatLogSummary.event(it) }
                    ChatLogSummary.error(it)?.let { msg -> log.warn("${ChatLogSummary.sid(id)} route=client-events $msg") }
                    emit(it)
                }
            }
        }
        return events
            .onStart { log.info("${ChatLogSummary.sid(id)} kind=subscription route=client-events start=true dir=${ChatLogSummary.dir(dir)}") }
            .onCompletion { cause ->
                if (cause == null || cause is CancellationException) {
                    log.info("${ChatLogSummary.sid(id)} kind=subscription route=client-events stop=true cancelled=${cause is CancellationException}")
                    return@onCompletion
                }
                log.warn("${ChatLogSummary.sid(id)} kind=subscription route=client-events stop=true failed message=${cause.message}", cause)
            }
    }

    /** Update config (model, agent/mode, temperature). */
    suspend fun updateConfig(dir: String, config: ConfigUpdateDto) {
        call(GROUP_SESSION) { updateConfig(dir, config) }
    }

    // ------ permission / question resolution ------

    /** Reply to a pending permission request. */
    suspend fun replyPermission(requestId: String, dir: String, reply: PermissionReplyDto) {
        call(GROUP_SESSION) { replyPermission(requestId, dir, reply) }
    }

    /** Save always-rules for a pending permission request. */
    suspend fun savePermissionRules(requestId: String, dir: String, rules: PermissionAlwaysRulesDto) {
        call(GROUP_SESSION) { savePermissionRules(requestId, dir, rules) }
    }

    /** Reply to a pending question with user answers. */
    suspend fun replyQuestion(requestId: String, dir: String, answers: QuestionReplyDto) {
        call(GROUP_SESSION) { replyQuestion(requestId, dir, answers) }
    }

    /** Reject a pending question. */
    suspend fun rejectQuestion(requestId: String, dir: String) {
        call(GROUP_SESSION) { rejectQuestion(requestId, dir) }
    }

    /** List pending permissions (caller filters by session ID). */
    suspend fun pendingPermissions(dir: String): List<PermissionRequestDto> =
        call(GROUP_SESSION) { pendingPermissions(dir) }

    /** List pending questions (caller filters by session ID). */
    suspend fun pendingQuestions(dir: String): List<QuestionRequestDto> =
        call(GROUP_SESSION) { pendingQuestions(dir) }
}
