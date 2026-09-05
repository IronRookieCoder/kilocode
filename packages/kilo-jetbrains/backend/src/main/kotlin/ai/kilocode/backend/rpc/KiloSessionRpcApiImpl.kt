@file:Suppress("UnstableApiUsage")

package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.backend.app.KiloBackendActivityManager
import ai.kilocode.backend.app.KiloBackendChatManager
import ai.kilocode.backend.app.KiloBackendSessionManager
import ai.kilocode.backend.app.CapabilityReleaseReason
import ai.kilocode.backend.app.KiloSessionCapabilities
import ai.kilocode.backend.workspace.KiloBackendWorkspaceManager
import ai.kilocode.log.ChatLogSummary
import ai.kilocode.rpc.KiloSessionRpcApi
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
import ai.kilocode.rpc.dto.PromptPartDto
import ai.kilocode.rpc.dto.QuestionReplyDto
import ai.kilocode.rpc.dto.QuestionRequestDto
import ai.kilocode.rpc.dto.SessionDto
import ai.kilocode.rpc.dto.SessionActivityDto
import ai.kilocode.rpc.dto.SessionListDto
import ai.kilocode.rpc.dto.SessionStatusDto
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.service
import ai.kilocode.log.KiloLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import ai.kilocode.backend.diff.DiffFullReconstruct
import java.nio.file.Files
import java.nio.file.Path

/**
 * Backend implementation of [KiloSessionRpcApi].
 *
 * Session CRUD routes through the [KiloBackendWorkspaceManager] to
 * get the correct workspace for a directory. Status tracking and
 * worktree directory management go directly to the
 * [KiloBackendSessionManager]. Chat operations delegate to
 * [KiloBackendChatManager].
 */
class KiloSessionRpcApiImpl internal constructor(
    private val appOverride: KiloBackendAppService? = null,
    private val log: KiloLog = LOG,
    private val source: Flow<ChatEventDto>? = null,
) : KiloSessionRpcApi {
    companion object {
        private val LOG = KiloLog.create(KiloSessionRpcApiImpl::class.java)
        private const val COMMIT_GENERATION_TIMEOUT_MS = 120_000L
        private const val COMMIT_DIFF_PROMPT_CAP = 16_000
        private const val COMMIT_PREVIOUS_CAP = 1_000
        private const val COMMIT_MESSAGE_MAX_LINES = 12
        private const val COMMIT_MESSAGE_MAX_CHARS = 500

        private val COMMIT_HEADER = Regex(
            "(?m)^\\s*(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\([\\w./ *-]+\\))?!?:\\s*\\S",
        )

        /** Hard output cap shared by both generation paths. */
        internal fun capCommitMessage(message: String): String =
            message.lines().take(COMMIT_MESSAGE_MAX_LINES).joinToString("\n").take(COMMIT_MESSAGE_MAX_CHARS).trim()

        /** Testable extraction: reply text (possibly full of reasoning) → final commit message. */
        internal fun commitMessageFrom(text: String): String {
            val stripped = text.replace("```[a-zA-Z0-9_-]*\n?".toRegex(), "").trim()
            val last = COMMIT_HEADER.findAll(stripped).lastOrNull()?.range?.first
            val picked = if (last != null) {
                stripped.substring(last)
            } else {
                stripped.split("\n\n").lastOrNull { it.isNotBlank() } ?: stripped
            }
            var result = picked.trim()
            if ((result.startsWith('\"') && result.endsWith('\"')) || (result.startsWith("'") && result.endsWith("'"))) {
                result = result.substring(1, result.length - 1)
            }
            return capCommitMessage(result)
        }
    }

    private val workspaces: KiloBackendWorkspaceManager
        get() = app.workspaces

    private val sessions: KiloBackendSessionManager
        get() = app.sessions

    private val chat: KiloBackendChatManager
        get() = app.chat

    private val activity: KiloBackendActivityManager
        get() = app.activity

    private val app: KiloBackendAppService
        get() = appOverride ?: service()

    override suspend fun list(directory: String): SessionListDto =
        ready { workspaces.get(directory).sessions() }

    override suspend fun recent(directory: String, limit: Int): SessionListDto =
        ready { sessions.recent(directory, limit) }

    override suspend fun create(directory: String): SessionDto {
        app.requireReady()
        log.info("create session: directory=$directory")
        val session = workspaces.get(directory).createSession()
        log.info("create session: id=${session.id}, directory=$directory")
        return session
    }

    override suspend fun get(id: String, directory: String): SessionDto {
        app.requireReady()
        val dir = sessions.getDirectory(id, directory)
        return sessions.get(id, dir)
    }

    override suspend fun delete(id: String, directory: String) {
        app.requireReady()
        log.info("delete session: id=$id, directory=$directory")
        val dir = sessions.getDirectory(id, directory)
        try {
            workspaces.get(dir).deleteSession(id)
        } finally {
            app.sessionCapabilities?.release(id, CapabilityReleaseReason.DELETE)
        }
    }

    override suspend fun rename(id: String, directory: String, title: String): ai.kilocode.rpc.dto.SessionDto {
        app.requireReady()
        val dir = sessions.getDirectory(id, directory)
        return sessions.rename(id, dir, title)
    }

    override suspend fun cloudSessions(directory: String, cursor: String?, limit: Int, gitUrl: String?): CloudSessionListDto =
        ready { sessions.cloudSessions(directory, cursor, limit, gitUrl) }

    override suspend fun importCloudSession(id: String, directory: String): SessionDto =
        ready { sessions.importCloudSession(id, directory) }

    override suspend fun statuses(): Flow<Map<String, SessionStatusDto>> =
        sessions.statuses

    override suspend fun activity(): Flow<Map<String, SessionActivityDto>> =
        activity.activity

    override suspend fun setDirectory(id: String, directory: String) =
        sessions.setDirectory(id, directory)

    override suspend fun getDirectory(id: String, fallback: String): String =
        sessions.getDirectory(id, fallback)

    // ------ chat ------

    override suspend fun enhancePrompt(directory: String, text: String): String =
        ready { chat.enhancePrompt(directory, text) }

    override suspend fun generateCommitMessage(input: CommitMessageRequestDto): CommitMessageResultDto =
        ready {
            val direct = chat.commitMessage(input)
            val generated = direct.message
            if (generated != null) return@ready direct.copy(message = capCommitMessage(generated))
            val missing = direct.error?.lowercase()?.let { error ->
                listOf("not found", "not_found", "http 404", "no proxy route").any(error::contains)
            } == true
            if (direct.noChanges || !missing) return@ready direct
            log.info("commit message: /commit-message unavailable, falling back to conversation api")
            generateViaConversation(input)
        }

    /**
     * Conversation-API fallback: runs the generation through a throwaway session so backends
     * without a dedicated /commit-message endpoint still work. The model selection travels
     * on the prompt itself ([PromptDto.providerID]/[PromptDto.modelID]).
     */
    private suspend fun generateViaConversation(input: CommitMessageRequestDto): CommitMessageResultDto {
        val directory = input.directory
        val prompt = commitPrompt(input) ?: return CommitMessageResultDto(
            error = "No changes found to generate a commit message for",
            noChanges = true,
        )
        val session = workspaces.get(directory).createSession()
        try {
            val parts = LinkedHashMap<String, StringBuilder>()
            val roles = HashMap<String, String>()
            val done = CompletableDeferred<Pair<Boolean, String?>>()  // (failed, text)

            fun text() = parts.values.joinToString("\n\n").trim()

            val outcome = coroutineScope {
            val collector = launch {
                chat.events.collect { event ->
                    when (event) {
                        is ChatEventDto.MessageUpdated -> roles[event.info.id] = event.info.role
                        is ChatEventDto.PartUpdated ->
                            if (event.sessionID == session.id && roles[event.part.messageID] == "assistant" && event.part.type == "text") {
                                parts.getOrPut(event.part.id) { StringBuilder() }.clear().append(event.part.text.orEmpty())
                            }
                        is ChatEventDto.PartDelta ->
                            if (event.sessionID == session.id && event.field == "text" && roles[event.messageID] == "assistant") {
                                parts.getOrPut(event.partID) { StringBuilder() }.append(event.delta)
                            }
                        is ChatEventDto.SessionResult ->
                            if (event.sessionID == session.id) done.complete(event.isError to text())
                        is ChatEventDto.SessionIdle ->
                            if (event.sessionID == session.id && parts.isNotEmpty()) done.complete(false to text())
                        is ChatEventDto.SessionStatusChanged ->
                            if (event.sessionID == session.id && event.status.type != "busy" && parts.isNotEmpty()) {
                                done.complete(false to text())
                            }
                        is ChatEventDto.Error ->
                            if (event.sessionID == session.id || event.sessionID == null) done.complete(true to null)
                        is ChatEventDto.PermissionAsked ->
                            if (event.sessionID == session.id) {
                                done.complete(true to "the backend agent asked for tool permissions")
                                runCatching { chat.abort(session.id, directory) }
                            }
                        else -> Unit
                    }
                }
            }
            try {
                chat.prompt(session.id, directory, PromptDto(
                    parts = listOf(PromptPartDto(type = "text", text = prompt)),
                    providerID = input.providerID,
                    modelID = input.modelID,
                ))
                withTimeoutOrNull(COMMIT_GENERATION_TIMEOUT_MS) { done.await() } ?: (true to null)
            } finally {
                collector.cancel()
            }
            }
            val (failed, text) = outcome
            if (failed || text.isNullOrBlank()) {
                runCatching { chat.abort(session.id, directory) }
                return CommitMessageResultDto(
                    error = text?.takeIf { it.isNotBlank() }
                        ?: "commit message generation did not return a result",
                )
            }
            return CommitMessageResultDto(message = cleanCommitMessage(text))
        } catch (e: CancellationException) {
            runCatching { chat.abort(session.id, directory) }
            throw e
        } finally {
            runCatching { workspaces.get(directory).deleteSession(session.id) }
        }
    }

    /**
     * Builds the generation prompt, or null when the repository is clean (nothing staged,
     * nothing modified, no untracked files).
     *
     * Mirrors the CLI's git-context rules: staged changes win; otherwise every working-tree
     * entry — including untracked files, which plain `git diff` does not show — is listed,
     * with untracked files summarized by a content preview.
     */
    private suspend fun commitPrompt(input: CommitMessageRequestDto): String? {
        val directory = input.directory
        val porcelain = runCatching { gitOutput(directory, "status", "--porcelain") }.getOrNull()
        if (porcelain === null) {
            log.warn("commit message: git status unavailable for $directory")
        } else if (porcelain.isBlank()) {
            return null
        }
        val useStaged = !runCatching { gitOutput(directory, "diff", "--cached", "--name-status") }
            .getOrNull().isNullOrBlank()

        val sb = StringBuilder()
        sb.append("Write a short Git commit message for the following changes.\n")
        sb.append("Format: conventional commits — type(scope): description in imperative mood, subject max 72 characters, ")
        sb.append("body only when necessary (1-3 lines). Keep the whole message under 150 characters when possible.\n")
        sb.append("Your reply MUST be the commit message itself and nothing else: start directly with the type prefix ")
        sb.append("(feat:, fix:, docs:, style:, refactor:, perf:, test:, build:, ci:, chore:, revert:) — ")
        sb.append("no reasoning, no explanations, no markdown fences.\n")
        sb.append("Do not use any tools, commands, or file reads — decide from the changes below alone.\n\n")
        input.previousMessage?.takeIf { it.isNotBlank() }?.let {
            val previous = it.trim().take(COMMIT_PREVIOUS_CAP)
            sb.append("The previous message was: \"" + previous + "\". Write a clearly different one.\n\n")
        }
        val branch = runCatching { gitOutput(directory, "branch", "--show-current") }.getOrNull()
        branch?.takeIf { it.isNotBlank() }?.let { sb.append("Branch: ").append(it).append("\n") }

        sb.append("\nChanged files:\n")
        val entries = porcelain.orEmpty().lines().filter { it.trimEnd().length >= 4 }
        var appended = 0
        for (raw in entries) {
            val line = raw.trimEnd()
            if (line.length < 4) continue
            val status = line.take(2).trim()
            val path = line.drop(3).trim().removeSurrounding("\"").substringAfter(" -> ")
            sb.append(status).append(' ').append(path).append('\n')
            when {
                status == "??" || status.endsWith("?") -> sb.append(untrackedPreview(directory, path))
                else -> sb.append(diffFor(directory, path, useStaged))
            }
            appended++
            if (sb.length >= COMMIT_DIFF_PROMPT_CAP) break
        }
        if (appended == 0) {
            sb.append("(git diff output was unavailable; describe the change from the context above.)\n")
        } else if (appended < entries.size) {
            sb.append("\n(" + (entries.size - appended) + " more changed files omitted for length.)\n")
        }
        return sb.toString().take(COMMIT_DIFF_PROMPT_CAP)
    }

    /** Unified diff for one path against the staged index when [staged], else the working tree. */
    private suspend fun diffFor(directory: String, path: String, staged: Boolean): String {
        val args = buildList {
            add("diff")
            if (staged) add("--cached")
            add("--")
            add(path)
        }
        return gitOutput(directory, *args.toTypedArray())?.take(4_000)?.let { "$it\n" }.orEmpty()
    }

    /** First bytes of an untracked file so the model can describe new files without tools. */
    private suspend fun untrackedPreview(directory: String, path: String): String = withContext(Dispatchers.IO) {
        val file = java.io.File(directory, path).takeIf { it.isFile } ?: return@withContext ""
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@withContext "(binary file)\n"
        if (content.contains('\u0000')) return@withContext "(binary file)\n"
        val head = content.take(1_500)
        buildList {
            add("New untracked file. Content preview:\n$head")
            if (content.length > head.length) add("\n…")
        }.joinToString("") + "\n"
    }

    /** Best-effort git output for [directory]; null when git is missing, fails, or the dir is not a checkout. */
    private suspend fun gitOutput(directory: String, vararg args: String): String? = withContext(Dispatchers.IO) {
        val work = java.io.File(directory).takeIf { it.isDirectory } ?: return@withContext null
        val command = GeneralCommandLine("git").withWorkDirectory(work).withParameters(*args)
        val output = CapturingProcessHandler(command).runProcess(10_000)
        output.stdout.takeIf { output.exitCode == 0 }?.trimEnd()
    }

    /**
     * Extracts the commit message from a model reply that may contain visible reasoning.
     * Models frequently think out loud before the final answer, so this takes everything from
     * the LAST conventional-commit header line to the end of the reply; when no header is
     * present it falls back to the last paragraph. Result is fence/quote stripped and capped.
     */
    private fun cleanCommitMessage(text: String): String = commitMessageFrom(text)

    override suspend fun prompt(id: String, directory: String, prompt: PromptDto) {
        app.requireReady()
        ensureCapability(app.sessionCapabilities, id, directory, log) // kilocode_change
        log.info("prompt RPC: session=$id, dir=$directory, parts=${prompt.parts.size}")
        chat.prompt(id, directory, prompt)
    }

    override suspend fun command(id: String, directory: String, command: String, arguments: String, prompt: PromptDto) {
        app.requireReady()
        log.info("command RPC: session=$id, dir=$directory, command=$command, parts=${prompt.parts.size}")
        chat.command(id, directory, command, arguments, prompt)
    }

    override suspend fun abort(id: String, directory: String) = ready {
        try {
            chat.abort(id, directory)
        } finally {
            app.sessionCapabilities?.release(id, CapabilityReleaseReason.ABORT)
        }
    }

    override suspend fun compact(id: String, directory: String, model: ModelSelectionDto) =
        ready { chat.compact(id, directory, model) }

    override suspend fun revert(id: String, directory: String, messageID: String, partID: String?) =
        ready { chat.revert(id, sessions.getDirectory(id, directory), messageID, partID) }

    override suspend fun deleteMessage(id: String, directory: String, messageID: String): Boolean =
        ready { chat.deleteMessage(id, sessions.getDirectory(id, directory), messageID) }

    override suspend fun unrevert(id: String, directory: String) =
        ready { chat.unrevert(id, sessions.getDirectory(id, directory)) }

    override suspend fun messages(id: String, directory: String): List<MessageWithPartsDto> =
        ready { chat.messages(id, directory) }

    override suspend fun diff(id: String, directory: String): List<DiffFileDto> = ready {
        // GET /session/:id/diff returns the cumulative, deduplicated, unquoted snapshot diff. Prefer it
        // over concatenating per-message summaries (which duplicate files per turn and skip unquoting).
        val api = app.api ?: throw IllegalStateException("Kilo API is unavailable")
        withContext(Dispatchers.IO) { api.sessionDiff(sessionID = id, directory = directory) }
            .mapNotNull { file ->
                val path = file.file ?: return@mapNotNull null
                DiffFileDto(path, file.additions.toInt(), file.deletions.toInt(), file.patch, file.status?.value)
            }
    }

    override suspend fun diffSides(sessionId: String?, directory: String, file: DiffFileDto, messageId: String?): DiffFileDto? {
        val patch = file.patch
        if (patch.isNullOrBlank()) return null
        log.info("diffSides start file=${file.file} session=${!sessionId.isNullOrBlank()} message=${!messageId.isNullOrBlank()} patch=${patch.length}")
        // 1) Authoritative: a CLI with full/file support returns whole before/after from the snapshot,
        //    correct even for historical turns. Older CLIs ignore the params, so we detect the missing
        //    content and fall through to local reconstruction.
        if (!sessionId.isNullOrBlank()) authoritative(sessionId, directory, file, messageId)?.let {
            log.info("diffSides authoritative file=${file.file} before=${it.before?.length ?: 0} after=${it.after?.length ?: 0}")
            return it
        }
        // 2) Fallback: read the working-tree file and reverse-apply the hunk patch to recover the whole
        //    "before". No CLI round-trip, so this works against any pinned CLI.
        return withContext(Dispatchers.IO) {
            val path = resolve(directory, file.file)
            val after = path?.let { runCatching { Files.readString(it) }.getOrNull() }
            val before = after?.let { DiffFullReconstruct.before(it, patch) }
            log.info("diffSides fallback file=${file.file} path=${path ?: "<missing>"} after=${after?.length ?: 0} before=${before?.length ?: 0}")
            if (after != null && before != null) file.copy(before = before, after = after) else null
        }
    }

    private fun resolve(directory: String, file: String): Path? {
        val direct = Path.of(directory).resolve(file).normalize()
        if (Files.isRegularFile(direct)) return direct
        // dev-only: a stored diff may reference another worktree (relative, or absolute into a sibling
        // worktree that isn't checked out here). Re-root onto the running worktree by trying progressively
        // shorter path suffixes until one exists, so full-file diffs work across dev worktrees.
        val root = System.getProperty("kilo.dev.worktree.root")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: return null
        val segs = Path.of(file).toList()
        for (i in segs.indices) {
            val candidate = segs.drop(i).fold(root) { acc, seg -> acc.resolve(seg) }.normalize()
            if (Files.isRegularFile(candidate)) return candidate
        }
        return null
    }

    // Ask the CLI for full before/after via GET /session/:id/diff?full=true&file=...; returns null when
    // the pinned CLI lacks full/file support (it omits before/after) so the caller falls back locally.
    private suspend fun authoritative(sessionId: String, directory: String, file: DiffFileDto, messageId: String?): DiffFileDto? {
        val api = app.api ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = (api.baseUrl.trimEnd('/') + "/").toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addPathSegment("session")
                    ?.addPathSegment(sessionId)
                    ?.addPathSegment("diff")
                    ?.addQueryParameter("directory", directory)
                    ?.addQueryParameter("full", "true")
                    ?.addQueryParameter("file", file.file)
                    ?.apply { if (!messageId.isNullOrBlank()) addQueryParameter("messageID", messageId) }
                    ?.build()
                    ?: return@runCatching null
                api.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        log.info("diffSides authoritative file=${file.file} http=${response.code} messageID=${messageId ?: "none"}")
                        return@runCatching null
                    }
                    val arr = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonArray
                    val item = arr.firstOrNull { it.jsonObject["file"]?.jsonPrimitive?.contentOrNull == file.file }?.jsonObject
                    val before = item?.get("before")?.jsonPrimitive?.contentOrNull
                    val after = item?.get("after")?.jsonPrimitive?.contentOrNull
                    log.info("diffSides authoritative file=${file.file} items=${arr.size} matched=${item != null} before=${before?.length ?: 0} after=${after?.length ?: 0}")
                    if (before != null && after != null) file.copy(before = before, after = after) else null
                }
            }.onFailure { log.info("diffSides authoritative file=${file.file} error=${it.message}") }.getOrNull()
        }
    }

    override suspend fun attachmentPart(id: String, directory: String, messageId: String, partId: String, attachmentKey: String?): PartDto? =
        ready { chat.attachmentPart(id, directory, messageId, partId, attachmentKey) }

    override suspend fun events(id: String, directory: String): Flow<ChatEventDto> =
        (source ?: chat.events)
            .onStart { log.info("${ChatLogSummary.sid(id)} kind=subscription route=rpc-events start=true dir=${ChatLogSummary.dir(directory)}") }
            .filter { event ->
                val sid = ChatLogSummary.sid(event)
                val passes = event is ChatEventDto.SessionCreated || sid == null || sid == id
                if (passes) log.debug { "${ChatLogSummary.sid(id)} pass=true ${ChatLogSummary.eventBody(event)}" }
                else log.debug { "${ChatLogSummary.sid(id)} pass=false srcSid=$sid ${ChatLogSummary.eventBody(event)}" }
                if (passes) {
                    ChatLogSummary.error(event)?.let { log.warn("${ChatLogSummary.sid(id)} route=rpc-events pass=true $it") }
                }
                if (passes && event is ChatEventDto.SessionStatusChanged && event.status.type != "busy") {
                    log.info(
                        "${ChatLogSummary.sid(id)} kind=status route=rpc-events pass=true " +
                            ChatLogSummary.status(event.status),
                    )
                }
                passes
            }
            .onCompletion { cause ->
                if (cause == null || cause is CancellationException) {
                    log.info("${ChatLogSummary.sid(id)} kind=subscription route=rpc-events stop=true cancelled=${cause is CancellationException}")
                    return@onCompletion
                }
                log.warn("${ChatLogSummary.sid(id)} kind=subscription route=rpc-events stop=true failed message=${cause.message}", cause)
            }

    override suspend fun updateConfig(directory: String, config: ConfigUpdateDto) =
        ready { chat.updateConfig(directory, config) }

    // ------ permission / question resolution ------

    override suspend fun replyPermission(requestId: String, directory: String, reply: PermissionReplyDto) {
        app.requireReady()
        log.info("replyPermission: requestId=$requestId, reply=${reply.reply}")
        chat.replyPermission(requestId, directory, reply)
    }

    override suspend fun savePermissionRules(requestId: String, directory: String, rules: PermissionAlwaysRulesDto) {
        app.requireReady()
        log.info("savePermissionRules: requestId=$requestId")
        chat.savePermissionRules(requestId, directory, rules)
    }

    override suspend fun replyQuestion(requestId: String, directory: String, answers: QuestionReplyDto) {
        app.requireReady()
        log.info("replyQuestion: requestId=$requestId, answers=${answers.answers.size}")
        chat.replyQuestion(requestId, directory, answers)
    }

    override suspend fun rejectQuestion(requestId: String, directory: String) {
        app.requireReady()
        log.info("rejectQuestion: requestId=$requestId")
        chat.rejectQuestion(requestId, directory)
    }

    override suspend fun pendingPermissions(directory: String): List<PermissionRequestDto> =
        ready { chat.pendingPermissions(directory) }

    override suspend fun pendingQuestions(directory: String): List<QuestionRequestDto> =
        ready { chat.pendingQuestions(directory) }

    private suspend fun <T> ready(block: suspend () -> T): T {
        app.requireReady()
        return block()
    }
}

// kilocode_change start
internal suspend fun ensureCapability(capabilities: KiloSessionCapabilities?, id: String, directory: String, log: KiloLog) {
    try {
        capabilities?.ensure(id, directory)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        log.warn("optional IDE capability failed for session=$id", error)
    }
}
// kilocode_change end
