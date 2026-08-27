package ai.kilocode.backend.app

import ai.kilocode.connection.BackendEvent
import ai.kilocode.connection.Transport
import ai.kilocode.connection.TransportException
import ai.kilocode.log.ChatLogSummary
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.ChatEventDto
import ai.kilocode.rpc.dto.ConfigUpdateDto
import ai.kilocode.rpc.dto.MessageWithPartsDto
import ai.kilocode.rpc.dto.ModelSelectionDto
import ai.kilocode.rpc.dto.PartDto
import ai.kilocode.rpc.dto.PermissionAlwaysRulesDto
import ai.kilocode.rpc.dto.PermissionReplyDto
import ai.kilocode.rpc.dto.PermissionRequestDto
import ai.kilocode.rpc.dto.PromptDto
import ai.kilocode.rpc.dto.QuestionReplyDto
import ai.kilocode.rpc.dto.QuestionRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Chat orchestrator that handles message sending, history loading,
 * and backend event routing for the agent chat UI.
 *
 * **Not an IntelliJ service** — owned by [KiloBackendAppService] which
 * calls [start] after [KiloAppState.Ready] and [stop] on disconnect.
 *
 * Request payloads and event data are JSON documents shaped by the
 * cross-module DTOs from `ai.kilocode.rpc.dto`.
 */
class KiloBackendChatManager(
    private val cs: CoroutineScope,
    private val log: KiloLog,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _events = MutableSharedFlow<ChatEventDto>(extraBufferCapacity = 128)
    val events: SharedFlow<ChatEventDto> = _events.asSharedFlow()

    private var transport: Transport? = null
    private var watcher: Job? = null

    fun start(transport: Transport, events: SharedFlow<BackendEvent>) {
        this.transport = transport
        if (watcher?.isActive == true) return
        watcher = cs.launch {
            events.collect { event ->
                val parsed = try {
                    json.decodeFromString(ChatEventDto.serializer(), event.data)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(
                        "route=chat-events parse=false type=${event.type} bytes=${event.data.length} ${ChatLogSummary.body(event.data)}",
                        e,
                    )
                    return@collect
                }
                log.debug { ChatLogSummary.event(parsed) }
                ChatLogSummary.error(parsed)?.let { error ->
                    log.warn(
                        "route=chat-events emit=true type=${event.type} bytes=${event.data.length} " +
                            "subscribers=${_events.subscriptionCount.value} $error",
                    )
                }
                if (parsed is ChatEventDto.SessionStatusChanged && parsed.status.type != "busy") {
                    log.info(
                        "${ChatLogSummary.sid(parsed.sessionID)} kind=status route=chat-events emit=true " +
                            "${ChatLogSummary.status(parsed.status)} bytes=${event.data.length}",
                    )
                }
                _events.emit(parsed)
            }
        }
        log.info("Chat manager started")
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        transport?.close()
        transport = null
        log.info("Chat manager stopped")
    }

    private fun requireTransport(): Transport =
        transport ?: throw IllegalStateException("Chat manager not started")

    // ------ prompt ------

    suspend fun enhancePrompt(dir: String, text: String): String {
        val t = requireTransport()
        val body = buildJsonObject { put("text", text) }.toString()
        return try {
            val raw = t.call("POST", "/enhance-prompt?directory=" + encode(dir), body)
            json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.content
                ?: throw RuntimeException("Enhance prompt response missing text")
        } catch (e: TransportException) {
            log.warn("enhance prompt failed: HTTP ${e.status}")
            e.body?.let { log.debug { "kind=enhancePrompt error=${ChatLogSummary.body(it)}" } }
            throw RuntimeException("Enhance prompt failed: HTTP ${e.status}", e)
        }
    }

    suspend fun prompt(id: String, dir: String, prompt: PromptDto) {
        val meta = if (log.isDebugEnabled) {
            "${ChatLogSummary.dir(dir)} ${ChatLogSummary.prompt(prompt)}"
        } else {
            "kind=prompt parts=${prompt.parts.size}"
        }
        log.info("${ChatLogSummary.sid(id)} kind=prompt $meta op=prompt_async")
        val t = requireTransport()
        val body = json.encodeToString(PromptDto.serializer(), prompt)
        log.debug { "${ChatLogSummary.sid(id)} ${ChatLogSummary.prompt(prompt)} ${ChatLogSummary.dir(dir)} op=prompt_async send=true" }
        try {
            t.call("POST", "/session/$id/prompt_async?directory=" + encode(dir), body)
            log.info("${ChatLogSummary.sid(id)} kind=prompt op=prompt_async accepted=true ${ChatLogSummary.prompt(prompt)}")
        } catch (e: TransportException) {
            log.warn("${ChatLogSummary.sid(id)} kind=prompt op=prompt_async failed http=${e.status}")
            e.body?.let { log.debug { "${ChatLogSummary.sid(id)} kind=prompt op=prompt_async error=${ChatLogSummary.body(it)}" } }
            val detail = e.body?.takeIf { it.isNotBlank() }?.let { ": ${ChatLogSummary.body(it)}" }.orEmpty()
            throw RuntimeException("prompt_async failed: HTTP ${e.status}$detail", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("${ChatLogSummary.sid(id)} kind=prompt op=prompt_async dir=${ChatLogSummary.dir(dir)} failed message=${e.message}", e)
            throw RuntimeException("prompt_async call failed: ${e.message}", e)
        }
    }

    suspend fun command(id: String, dir: String, command: String, args: String, prompt: PromptDto) {
        log.info("${ChatLogSummary.sid(id)} kind=command command=$command args=${args.length} parts=${prompt.parts.size}")
        val promptJson = json.encodeToJsonElement(PromptDto.serializer(), prompt).jsonObject
        val body = buildJsonObject {
            put("command", command)
            put("arguments", args)
            promptJson.forEach { (k, v) -> put(k, v) }
        }.toString()
        post("/session/$id/command?directory=" + encode(dir), body, "command", "${ChatLogSummary.sid(id)} kind=command command=$command")
    }

    // ------ abort ------

    suspend fun abort(id: String, dir: String) {
        log.debug { "${ChatLogSummary.sid(id)} kind=abort ${ChatLogSummary.dir(dir)} op=abort send=true" }
        try {
            requireTransport().call("POST", "/session/$id/abort?directory=" + encode(dir), "{}")
            log.debug { "${ChatLogSummary.sid(id)} kind=abort op=abort ok=true" }
        } catch (e: Exception) {
            log.warn("${ChatLogSummary.sid(id)} kind=abort failed message=${e.message}")
        }
    }

    // ------ compact ------

    suspend fun compact(id: String, dir: String, model: ModelSelectionDto) {
        log.info("${ChatLogSummary.sid(id)} kind=compact ${ChatLogSummary.dir(dir)} model=${model.providerID}/${model.modelID} op=summarize")
        val body = json.encodeToString(ModelSelectionDto.serializer(), model)
        try {
            requireTransport().call("POST", "/session/$id/summarize?directory=" + encode(dir), body)
            log.debug { "${ChatLogSummary.sid(id)} kind=compact op=summarize ok=true" }
        } catch (e: TransportException) {
            log.warn("${ChatLogSummary.sid(id)} kind=compact op=summarize failed http=${e.status}")
            e.body?.let { log.debug { "${ChatLogSummary.sid(id)} kind=compact op=summarize error=${ChatLogSummary.body(it)}" } }
            throw RuntimeException("summarize failed: HTTP ${e.status}", e)
        } catch (e: Exception) {
            log.warn("${ChatLogSummary.sid(id)} kind=compact op=summarize failed message=${e.message}", e)
            throw RuntimeException("summarize call failed: ${e.message}", e)
        }
    }

    suspend fun revert(id: String, dir: String, message: String, part: String?) {
        log.info("${ChatLogSummary.sid(id)} kind=revert ${ChatLogSummary.dir(dir)} message=$message part=${part ?: "none"}")
        val body = buildJsonObject {
            put("messageID", message)
            part?.let { put("partID", it) }
        }.toString()
        postCancellable("/session/$id/revert?directory=" + encode(dir), body, "revert", "${ChatLogSummary.sid(id)} kind=revert")
    }

    suspend fun deleteMessage(id: String, dir: String, message: String): Boolean {
        log.info("${ChatLogSummary.sid(id)} kind=deleteMessage ${ChatLogSummary.dir(dir)} message=$message")
        return try {
            val raw = requireTransport().call("DELETE", "/session/$id/message/$message?directory=" + encode(dir)).trim()
            raw != "false"
        } catch (e: Exception) {
            log.warn("${ChatLogSummary.sid(id)} kind=deleteMessage failed message=${e.message}")
            false
        }
    }

    suspend fun unrevert(id: String, dir: String) {
        log.info("${ChatLogSummary.sid(id)} kind=unrevert ${ChatLogSummary.dir(dir)}")
        postCancellable("/session/$id/unrevert?directory=" + encode(dir), "{}", "unrevert", "${ChatLogSummary.sid(id)} kind=unrevert")
    }

    // ------ messages ------

    suspend fun messages(id: String, dir: String): List<MessageWithPartsDto> {
        log.debug { "${ChatLogSummary.sid(id)} kind=history ${ChatLogSummary.dir(dir)} op=messages send=true" }
        return try {
            val raw = requireTransport().call("GET", "/session/$id/message?directory=" + encode(dir))
            val parsed = json.decodeFromString(ListSerializer(MessageWithPartsDto.serializer()), raw)
            log.debug { "${ChatLogSummary.sid(id)} ${ChatLogSummary.history(parsed)} op=messages ok=true" }
            parsed
        } catch (e: Exception) {
            log.warn("${ChatLogSummary.sid(id)} kind=history op=messages failed message=${e.message}")
            emptyList()
        }
    }

    suspend fun attachmentPart(id: String, dir: String, message: String, part: String, key: String?): PartDto? {
        return messages(id, dir)
            .firstOrNull { it.info.id == message }
            ?.parts
            ?.firstOrNull {
                if (it.type != "file") return@firstOrNull false
                if (!key.isNullOrBlank()) attachmentKey(it.id, it.filename.orEmpty(), it.url.orEmpty()) == key
                else it.id == part
            }
    }

    // ------ config update ------

    suspend fun updateConfig(dir: String, update: ConfigUpdateDto) {
        val partial = Json { explicitNulls = false }.encodeToString(ConfigUpdateDto.serializer(), update)
        try {
            requireTransport().call("PATCH", "/global/config", partial)
            log.info("Config updated: model=${update.model}, agent=${update.agent}, temp=${update.temperature}")
        } catch (e: Exception) {
            log.warn("config update failed: ${e.message}")
        }
    }

    // ------ permission / question ------

    suspend fun replyPermission(requestId: String, dir: String, reply: PermissionReplyDto) {
        log.debug { "kind=permission rid=$requestId ${ChatLogSummary.dir(dir)} op=replyPermission reply=${reply.reply} send=true" }
        val body = json.encodeToString(PermissionReplyDto.serializer(), reply)
        post("/permission/$requestId/reply?directory=" + encode(dir), body, "replyPermission", "kind=permission rid=$requestId")
    }

    suspend fun savePermissionRules(requestId: String, dir: String, rules: PermissionAlwaysRulesDto) {
        log.debug { "kind=permission rid=$requestId ${ChatLogSummary.dir(dir)} op=savePermissionRules approved=${rules.approvedAlways.size} denied=${rules.deniedAlways.size} send=true" }
        val body = json.encodeToString(PermissionAlwaysRulesDto.serializer(), rules)
        post("/permission/$requestId/always-rules?directory=" + encode(dir), body, "savePermissionRules", "kind=permission rid=$requestId")
    }

    suspend fun replyQuestion(requestId: String, dir: String, answers: QuestionReplyDto) {
        log.debug { "kind=question rid=$requestId ${ChatLogSummary.dir(dir)} op=replyQuestion answers=${answers.answers.size} send=true" }
        val body = json.encodeToString(QuestionReplyDto.serializer(), answers)
        post("/question/$requestId/reply?directory=" + encode(dir), body, "replyQuestion", "kind=question rid=$requestId")
    }

    suspend fun rejectQuestion(requestId: String, dir: String) {
        log.debug { "kind=question rid=$requestId ${ChatLogSummary.dir(dir)} op=rejectQuestion send=true" }
        post("/question/$requestId/reject?directory=" + encode(dir), "{}", "rejectQuestion", "kind=question rid=$requestId")
    }

    suspend fun pendingPermissions(dir: String): List<PermissionRequestDto> {
        val raw = get("/permission?directory=" + encode(dir), "pendingPermissions") ?: return emptyList()
        val parsed = json.decodeFromString(ListSerializer(PermissionRequestDto.serializer()), raw)
        log.debug { "kind=permission ${ChatLogSummary.dir(dir)} op=pendingPermissions ok=true count=${parsed.size}" }
        return parsed
    }

    suspend fun pendingQuestions(dir: String): List<QuestionRequestDto> {
        val raw = get("/question?directory=" + encode(dir), "pendingQuestions") ?: return emptyList()
        val parsed = json.decodeFromString(ListSerializer(QuestionRequestDto.serializer()), raw)
        log.debug { "kind=question ${ChatLogSummary.dir(dir)} op=pendingQuestions ok=true count=${parsed.size}" }
        return parsed
    }

    // ------ utilities ------

    private suspend fun post(path: String, body: String, op: String, meta: String, strict: Boolean = false) {
        try {
            requireTransport().call("POST", path, body)
            log.debug { "$meta op=$op ok=true" }
        } catch (e: Exception) {
            log.warn("$op failed: ${e.message}")
            if (e is TransportException) e.body?.let { log.debug { "$meta op=$op error=${ChatLogSummary.body(it)}" } }
            if (strict) throw RuntimeException("$op failed: ${e.message}", e)
        }
    }

    private suspend fun postCancellable(path: String, body: String, op: String, meta: String) {
        try {
            requireTransport().call("POST", path, body)
            log.debug { "$meta op=$op ok=true" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("$op failed: ${e.message}")
            if (e is TransportException) e.body?.let { log.debug { "$meta op=$op error=${ChatLogSummary.body(it)}" } }
            throw RuntimeException("$op failed: ${e.message}", e)
        }
    }

    private suspend fun get(path: String, op: String): String? {
        return try {
            val raw = requireTransport().call("GET", path)
            log.debug { "op=$op ok=true" }
            raw
        } catch (e: Exception) {
            log.warn("$op failed: ${e.message}")
            null
        }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun attachmentKey(part: String, name: String, url: String): String {
        val value = listOf(part, name, url).joinToString("\u0000")
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

}
