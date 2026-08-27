@file:Suppress("UnstableApiUsage")

package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.KiloAgentBehaviorRpcApi
import ai.kilocode.rpc.dto.AgentCreateDto
import ai.kilocode.rpc.dto.AgentDetailDto
import ai.kilocode.rpc.dto.CommandDto
import ai.kilocode.rpc.dto.CommandFileDto
import ai.kilocode.rpc.dto.ConfigPatchDto
import ai.kilocode.rpc.dto.McpConfigDto
import ai.kilocode.rpc.dto.McpServerConfigDto
import ai.kilocode.rpc.dto.McpStatusDto
import ai.kilocode.rpc.dto.PermissionRuleItemDto
import ai.kilocode.rpc.dto.SkillDto
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class KiloAgentBehaviorRpcApiImpl(private val backend: KiloBackendAppService? = null) : KiloAgentBehaviorRpcApi {
    companion object {
        private val LOG = KiloLog.create(KiloAgentBehaviorRpcApiImpl::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        private val saved = ConcurrentHashMap<String, SavedMcp>()
        private val extensions = setOf("md", "markdown", "txt", "text", "html", "htm")
        private const val CLAUDE_COMPAT_KEY = "kilo.claudeCodeCompat"
    }

    private val app: KiloBackendAppService get() = backend ?: service()

    override suspend fun agents(directory: String): List<AgentDetailDto> {
        app.requireReady()
        val removable = agentRemovable(request(directory, "/agent", null))
        return array(request(directory, "/app/agents", null)).mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            AgentDetailDto(
                name = name,
                displayName = obj.str("displayName"),
                description = obj.str("description"),
                mode = obj.str("mode") ?: "primary",
                native = obj.flagOrNull("native"),
                removable = removable[name] ?: false,
                hidden = obj.flagOrNull("hidden"),
                deprecated = obj.flagOrNull("deprecated"),
                permission = rules(obj["permission"]),
            )
        }
    }

    override suspend fun skills(directory: String): List<SkillDto> {
        val items = array(request(directory, "/skill", null)).mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val location = obj.str("location") ?: return@mapNotNull null
            SkillDto(
                name = name,
                description = obj.str("description"),
                location = location,
                content = obj.str("content"),
            )
        }
        return items.map { item ->
            val editable = editable(item)
            item.copy(content = skillContent(item) ?: item.content, editable = editable)
        }
    }

    override suspend fun removeSkill(directory: String, location: String): Boolean =
        post(directory, "/kilocode/skill/remove", JsonObject(mapOf("location" to JsonPrimitive(location))))

    override suspend fun reloadSkills(directory: String): Boolean {
        LOG.info("Skills reload requested dir=$directory")
        if (hasActiveSession(directory, "Skills")) {
            LOG.warn("Skills reload blocked by active session dir=$directory")
            return false
        }
        runCatching { post(directory, "/instance/reload") }.onFailure { err ->
            LOG.warn("Skills reload failed dir=$directory", err)
        }.getOrThrow()
        LOG.info("Skills reload succeeded dir=$directory")
        return true
    }

    override suspend fun saveSkill(directory: String, location: String, content: String): Boolean {
        LOG.info("Skill save requested dir=$directory location=$location")
        app.requireReady()
        val paths = knownSkills(directory)
        val path = writablePath(directory, location, paths) ?: return false
        withContext(Dispatchers.IO) {
            Files.writeString(path, content, StandardCharsets.UTF_8)
        }
        LOG.info("Skill file saved dir=$directory path=$path bytes=${content.toByteArray(StandardCharsets.UTF_8).size}")
        LOG.info("Skill save reload deferred dir=$directory path=$path")
        return true
    }

    override suspend fun saveSkills(directory: String, edits: Map<String, String>): Boolean {
        LOG.info("Skills save requested dir=$directory count=${edits.size}")
        app.requireReady()
        val known = knownSkills(directory)
        val paths = edits.mapNotNull { (location, content) ->
            val path = writablePath(directory, location, known) ?: return false
            path to content
        }
        withContext(Dispatchers.IO) {
            for ((path, content) in paths) Files.writeString(path, content, StandardCharsets.UTF_8)
        }
        LOG.info("Skill files saved dir=$directory count=${paths.size}")
        LOG.info("Skills save reload deferred dir=$directory count=${paths.size}")
        return true
    }

    override suspend fun removeAgent(directory: String, name: String): Boolean =
        post(directory, "/kilocode/agent/remove", JsonObject(mapOf("name" to JsonPrimitive(name))))

    override suspend fun createAgent(directory: String, input: AgentCreateDto): Boolean {
        app.requireReady()
        val body = buildJsonObject {
            put("id", input.name)
            put("prompt", input.prompt)
            put("scope", scope(input.scope))
            put("description", input.description)
            put("mode", mode(input.mode))
        }
        app.transport().use {
            it.call("PUT", "/agent-builder/${encodePath(input.name)}?directory=${encode(directory)}", body.toString())
        }
        return true
    }

    override suspend fun commands(directory: String): List<CommandDto> =
        array(request(directory, "/command", null)).mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            CommandDto(
                name = name,
                description = obj.str("description"),
                agent = obj.str("agent"),
                model = obj.str("model"),
                variant = obj.str("variant"),
                source = obj.str("source"),
                template = obj.str("template"),
                subtask = obj.flagOrNull("subtask"),
                hints = obj.strings("hints") ?: emptyList(),
            )
        }

    override suspend fun commandFiles(directory: String): List<CommandFileDto> =
        array(request(directory, "/kilocode/command/files", null)).mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val location = obj.str("location") ?: return@mapNotNull null
            CommandFileDto(
                name = name,
                description = obj.str("description"),
                agent = obj.str("agent"),
                model = obj.str("model"),
                variant = obj.str("variant"),
                source = obj.str("source"),
                builtin = obj.bool("builtin"),
                location = location,
                editable = obj.bool("editable"),
                content = obj.str("content"),
                subtask = obj.flagOrNull("subtask"),
                hints = obj.strings("hints") ?: emptyList(),
            )
        }

    override suspend fun removeCommand(directory: String, location: String): Boolean =
        post(directory, "/kilocode/command/remove", JsonObject(mapOf("location" to JsonPrimitive(location))))

    override suspend fun reloadCommands(directory: String): Boolean {
        LOG.info("Commands reload requested dir=$directory")
        if (hasActiveSession(directory, "Commands")) {
            LOG.warn("Commands reload blocked by active session dir=$directory")
            return false
        }
        runCatching { post(directory, "/instance/reload") }.onFailure { err ->
            LOG.warn("Commands reload failed dir=$directory", err)
        }.getOrThrow()
        LOG.info("Commands reload succeeded dir=$directory")
        return true
    }

    override suspend fun saveCommands(directory: String, edits: Map<String, String>): Boolean {
        LOG.info("Commands save requested dir=$directory count=${edits.size}")
        app.requireReady()
        val known = knownCommands(directory)
        val roots = commandRoots(directory)
        val paths = edits.map { (location, content) ->
            val path = writableCommandPath(directory, location, known, roots) ?: return false
            path to content
        }
        withContext(Dispatchers.IO) {
            for ((path, content) in paths) {
                Files.createDirectories(path.parent)
                Files.writeString(path, content, StandardCharsets.UTF_8)
            }
        }
        LOG.info("Command files saved dir=$directory count=${paths.size}")
        LOG.info("Commands save reload deferred dir=$directory count=${paths.size}")
        return true
    }

    override suspend fun mcpStatus(directory: String): List<McpStatusDto> {
        val root = runCatching { json.parseToJsonElement(request(directory, "/mcp", null)) }.getOrNull()
        val items = when (root) {
            is JsonArray -> root.mapNotNull(::mcpStatus)
            is JsonObject -> root.entries.mapNotNull { (name, item) -> mcpStatus(item, name) }
            else -> emptyList()
        }
        LOG.info("MCP status returned dir=$directory count=${items.size}")
        return items
    }

    override suspend fun mcpConfig(directory: String): Map<String, McpServerConfigDto> {
        app.requireReady()
        val global = app.config?.mcp ?: emptyMap()
        val workspace = if (directory.isBlank()) emptyMap() else try {
            parseMcpConfig(request(directory, "/config", null))
        } catch (e: Exception) {
            LOG.warn("MCP workspace config fetch failed dir=$directory: ${e.message}", e)
            emptyMap()
        }
        val items = buildMap {
            for (name in global.keys + workspace.keys) {
                val ws = workspace[name]
                val gl = global[name]
                val cfg = ws ?: gl ?: continue
                val scope = if (ws != null && (gl == null || ws != gl)) "workspace" else "global"
                put(name, McpServerConfigDto(cfg, scope))
            }
        }
        return withSavedMcp(directory, items)
    }

    override suspend fun saveMcp(directory: String, name: String, scope: String, config: McpConfigDto?): Boolean {
        app.requireReady()
        if (scope == "workspace") {
            val body = JsonObject(mapOf("mcp" to JsonObject(mapOf(name to (config?.let(::mcpJson) ?: JsonNull)))))
            app.transport().use {
                it.call("PATCH", "/config?directory=${encode(directory)}", body.toString())
            }
        } else {
            app.updateConfig(ConfigPatchDto(mcp = mapOf(name to config)))
        }
        saveMcpOverride(directory, name, scope, config)
        return true
    }

    override suspend fun mcpConnect(directory: String, name: String): Boolean = post(directory, "/mcp/${encodePath(name)}/connect")

    override suspend fun mcpDisconnect(directory: String, name: String): Boolean = post(directory, "/mcp/${encodePath(name)}/disconnect")

    override suspend fun mcpAuthenticate(directory: String, name: String): Boolean =
        post(directory, "/mcp/${encodePath(name)}/auth/authenticate")

    override suspend fun claudeCodeCompat(): Boolean = compat()

    override suspend fun setClaudeCodeCompat(value: Boolean): Boolean {
        compatSet(value)
        app.restart()
        return value
    }

    // ------ transport helpers ------

    private suspend fun request(directory: String, path: String, body: JsonObject?): String = withContext(Dispatchers.IO) {
        val route = "$path?directory=${encode(directory)}"
        val raw = app.transport().use {
            if (body == null) it.call("GET", route) else it.call("POST", route, body.toString())
        }
        raw.ifBlank { "{}" }
    }

    private suspend fun post(directory: String, path: String, body: JsonObject = JsonObject(emptyMap())): Boolean {
        request(directory, path, body)
        return true
    }

    // ------ JSON parsing ------

    private fun array(raw: String): List<JsonElement> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> (root["data"] as? JsonArray)?.toList() ?: emptyList()
            else -> emptyList()
        }
    }

    private fun agentRemovable(raw: String): Map<String, Boolean> =
        array(raw).mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            name to removable(obj)
        }.toMap()

    private fun removable(obj: JsonObject): Boolean {
        if (obj.flagOrNull("native") == true) return false
        val opts = obj["options"].obj()
        if (obj.str("source") == "organization" || opts?.str("source") == "organization") return false
        if (opts?.containsKey("reference") == true || opts?.containsKey("resolved") == true) return false
        return true
    }

    private fun mcpStatus(item: JsonElement, fallback: String? = null): McpStatusDto? {
        val obj = item.obj() ?: return null
        val name = obj.str("name") ?: fallback ?: return null
        return McpStatusDto(
            name = name,
            status = obj.str("status") ?: obj.str("state") ?: "unknown",
            error = obj.str("error"),
        )
    }

    private fun parseMcpConfig(raw: String): Map<String, McpConfigDto> {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyMap()
        val mcp = obj["mcp"].obj() ?: return emptyMap()
        return mcp.entries.mapNotNull { (name, elem) ->
            val item = elem.obj() ?: return@mapNotNull null
            name to McpConfigDto(
                type = item.str("type"),
                command = item.strings("command")?.takeIf { it.isNotEmpty() },
                url = item.str("url"),
                environment = item.map("environment")?.takeIf { it.isNotEmpty() }
                    ?: item.map("env")?.takeIf { it.isNotEmpty() },
                headers = item.map("headers")?.takeIf { it.isNotEmpty() },
                enabled = item.flagOrNull("enabled"),
                timeout = item.longOrNull("timeout"),
            )
        }.toMap()
    }

    private fun mcpJson(cfg: McpConfigDto): JsonObject = buildJsonObject {
        cfg.type?.let { put("type", it) }
        cfg.command?.takeIf { it.isNotEmpty() }?.let { put("command", JsonArray(it.map(::JsonPrimitive))) }
        cfg.url?.let { put("url", it) }
        cfg.environment?.takeIf { it.isNotEmpty() }?.let { env -> put("environment", JsonObject(env.mapValues { JsonPrimitive(it.value) })) }
        cfg.headers?.takeIf { it.isNotEmpty() }?.let { headers -> put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) })) }
        cfg.enabled?.let { put("enabled", it) }
        cfg.timeout?.let { put("timeout", it) }
    }

    private fun rules(elem: JsonElement?): List<PermissionRuleItemDto> {
        val list = elem as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val tool = obj.str("tool") ?: return@mapNotNull null
            val action = obj.str("action") ?: return@mapNotNull null
            PermissionRuleItemDto(tool = tool, pattern = obj.str("pattern"), action = action)
        }
    }

    private fun JsonElement?.obj(): JsonObject? = (this as? JsonObject)

    private fun JsonObject.str(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.flagOrNull(name: String): Boolean? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()

    private fun JsonObject.bool(name: String): Boolean = flagOrNull(name) ?: false

    private fun JsonObject.longOrNull(name: String): Long? =
        runCatching { this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()

    private fun JsonObject.strings(name: String): List<String>? =
        (this[name] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun JsonObject.map(name: String): Map<String, String>? =
        this[name].obj()?.entries?.mapNotNull { (key, value) ->
            val content = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: return@mapNotNull null
            key to content
        }?.toMap()

    // ------ session guards ------

    private suspend fun hasActiveSession(directory: String, label: String): Boolean {
        val active = app.sessions.statuses.value.filterValues { it.type != "idle" }
        if (active.isNotEmpty()) {
            LOG.info("$label reload active statuses dir=$directory count=${active.size} types=${active.values.map { it.type }.distinct()}")
            return true
        }
        val permissions = runCatching { app.chat.pendingPermissions(directory) }.onFailure { err ->
            LOG.warn("$label reload pending permission check failed dir=$directory", err)
        }.getOrDefault(emptyList())
        if (permissions.isNotEmpty()) {
            LOG.info("$label reload pending permissions dir=$directory count=${permissions.size}")
            return true
        }
        val questions = runCatching { app.chat.pendingQuestions(directory) }.onFailure { err ->
            LOG.warn("$label reload pending question check failed dir=$directory", err)
        }.getOrDefault(emptyList())
        if (questions.isNotEmpty()) {
            LOG.info("$label reload pending questions dir=$directory count=${questions.size}")
            return true
        }
        return false
    }

    // ------ local skill/command file plumbing ------

    private suspend fun skillContent(skill: SkillDto): String? {
        val path = resolveSkillPath(skill.location) ?: return null
        return runCatching {
            withContext(Dispatchers.IO) {
                if (!Files.isRegularFile(path)) null else Files.readString(path, StandardCharsets.UTF_8)
            }
        }.onFailure { err ->
            LOG.warn("Skill content read failed: $path", err)
        }.getOrNull()
    }

    private fun editable(skill: SkillDto): Boolean {
        val path = resolveSkillPath(skill.location) ?: return false
        if (urlCached(path)) return false
        return true
    }

    private suspend fun knownSkills(directory: String): Set<Path> {
        val items = array(request(directory, "/skill", null)).mapNotNull { item ->
            val obj = item.obj() ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            val location = obj.str("location") ?: return@mapNotNull null
            SkillDto(name = name, description = obj.str("description"), location = location, content = obj.str("content"))
        }
        return items.mapNotNull { item -> resolveEditablePath(item) }.toSet()
    }

    private suspend fun knownCommands(directory: String): Set<Path> {
        val items = commandFiles(directory)
        return items.mapNotNull { item -> resolveEditableCommandPath(item) }.toSet()
    }

    private fun writablePath(directory: String, location: String, known: Set<Path>): Path? {
        val path = resolveSkillPath(location)
        if (path == null) {
            LOG.warn("Skill save rejected: invalid location dir=$directory location=$location")
            return null
        }
        if (path !in known) {
            LOG.warn("Skill save rejected: unknown skill dir=$directory path=$path")
            return null
        }
        return path
    }

    private fun writableCommandPath(directory: String, location: String, known: Set<Path>, roots: Set<Path>): Path? {
        val path = resolveCommandPath(location)
        if (path == null) {
            LOG.warn("Command save rejected: invalid location dir=$directory location=$location")
            return null
        }
        if (path in known || newCommandPath(path, roots)) return path
        LOG.warn("Command save rejected: unknown command dir=$directory path=$path")
        return null
    }

    private fun resolveEditablePath(skill: SkillDto): Path? {
        val path = resolveSkillPath(skill.location) ?: return null
        if (urlCached(path)) return null
        return path
    }

    private fun resolveEditableCommandPath(command: CommandFileDto): Path? {
        if (!command.editable) return null
        return resolveCommandPath(command.location)
    }

    private fun resolveSkillPath(location: String): Path? {
        val raw = normalizeWorkspacePath(location) ?: return null
        val path = try {
            Path.of(raw).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        if (!path.isAbsolute || !isSkillFile(path)) return null
        return path
    }

    private fun resolveCommandPath(location: String): Path? {
        val raw = normalizeWorkspacePath(location) ?: return null
        val path = try {
            Path.of(raw).normalize()
        } catch (_: InvalidPathException) {
            return null
        }
        if (!path.isAbsolute || path.fileName?.toString()?.endsWith(".md") != true) return null
        return path
    }

    private fun normalizeWorkspacePath(location: String): String? {
        val trimmed = location.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return null
        return trimmed.takeIf { it.isNotBlank() }
    }

    private suspend fun commandRoots(directory: String): Set<Path> = buildSet {
        addProjectCommandRoots(this, directory)
        val paths = runCatching { request(directory, "/path", null) }.getOrNull()
        val config = paths?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonObject["config"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        if (config != null) addConfigCommandRoots(this, config)
        val home = paths?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonObject["home"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        if (home != null) addHomeCommandRoots(this, home)
    }

    private fun addProjectCommandRoots(roots: MutableSet<Path>, dir: String) {
        val base = try {
            Path.of(dir).normalize()
        } catch (_: InvalidPathException) {
            return
        }
        for (cfg in listOf(".kilo", ".kilocode")) {
            for (name in listOf("command", "commands")) roots.add(base.resolve(cfg).resolve(name).normalize())
        }
    }

    private fun addConfigCommandRoots(roots: MutableSet<Path>, dir: String) {
        val base = try {
            Path.of(dir).normalize()
        } catch (_: InvalidPathException) {
            return
        }
        for (name in listOf("command", "commands")) roots.add(base.resolve(name).normalize())
    }

    private fun addHomeCommandRoots(roots: MutableSet<Path>, home: String) {
        val base = try {
            Path.of(home).normalize()
        } catch (_: InvalidPathException) {
            return
        }
        for (cfg in listOf(".kilo", ".kilocode")) addConfigCommandRoots(roots, base.resolve(cfg).toString())
    }

    private fun newCommandPath(path: Path, roots: Set<Path>): Boolean {
        return roots.any { root -> path.startsWith(root) }
    }

    private fun urlCached(path: Path): Boolean {
        return cacheRoots().any { root -> path.startsWith(root.resolve("kilo").resolve("skills").normalize()) }
    }

    private fun cacheRoots(): Set<Path> = buildSet {
        val home = System.getProperty("user.home")
        add(Path.of(cacheRoot()).normalize())
        add(Path.of(home, ".cache").normalize())
        add(Path.of(home, "Library", "Caches").normalize())
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it).normalize()) }
        add(Path.of(home, "AppData", "Local").normalize())
    }

    private fun cacheRoot(): String {
        val xdg = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
        if (xdg != null) return xdg
        val home = System.getProperty("user.home")
        if (SystemInfo.isMac) return Path.of(home, "Library", "Caches").toString()
        if (SystemInfo.isWindows) return System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: Path.of(home, "AppData", "Local").toString()
        return Path.of(home, ".cache").toString()
    }

    // ------ saved MCP overrides ------

    private fun withSavedMcp(directory: String, items: Map<String, McpServerConfigDto>): Map<String, McpServerConfigDto> = buildMap {
        putAll(items)
        for (item in saved.values) {
            if (item.scope == "workspace" && item.directory != directory) continue
            val cfg = item.config ?: continue
            put(item.name, McpServerConfigDto(cfg, item.scope))
        }
    }

    private fun saveMcpOverride(directory: String, name: String, scope: String, config: McpConfigDto?) {
        val key = mcpKey(if (scope == "workspace") directory else "", name)
        saved.remove(mcpKey(directory, name))
        saved.remove(mcpKey("", name))
        if (config == null) {
            saved.remove(key)
            return
        }
        saved[key] = SavedMcp(
            directory = if (scope == "workspace") directory else "",
            name = name,
            scope = scope,
            config = config,
        )
    }

    private fun mcpKey(directory: String, name: String): String = "$directory\u0000$name"

    // ------ misc ------

    private fun compat(): Boolean =
        runCatching { PropertiesComponent.getInstance().getBoolean(CLAUDE_COMPAT_KEY, false) }.getOrDefault(false)

    private fun compatSet(value: Boolean) {
        runCatching { PropertiesComponent.getInstance().setValue(CLAUDE_COMPAT_KEY, value.toString()) }
    }

    private fun scope(value: String): String = when (value) {
        "global" -> "global"
        else -> "project"
    }

    private fun mode(value: String): String = when (value) {
        "subagent" -> "subagent"
        "all" -> "all"
        else -> "primary"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

    private fun isSkillFile(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (name == "SKILL.md") return true
        return name.substringAfterLast('.', "").lowercase() in extensions
    }

    private data class SavedMcp(
        val directory: String,
        val name: String,
        val scope: String,
        val config: McpConfigDto?,
    )
}
