package ai.kilocode.backend.app

import ai.kilocode.connection.Transport
import ai.kilocode.log.KiloLog
import ai.kilocode.rpc.dto.ModelFavoriteUpdateDto
import ai.kilocode.rpc.dto.ModelSelectionDto
import ai.kilocode.rpc.dto.ModelSelectionUpdateDto
import ai.kilocode.rpc.dto.ModelStateDto
import ai.kilocode.rpc.dto.ModelVariantUpdateDto
import ai.kilocode.rpc.dto.PathStateDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KiloBackendModelStateManager(
    private val log: KiloLog,
) {
    companion object {
        private val DEFAULT_DIR = Path.of(System.getProperty("user.home"), ".local", "state", "kilo")
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    private val mutex = Mutex()

    private var transport: Transport? = null
    private var file: Path? = null

    fun start(transport: Transport) {
        this.transport = transport
        file = null
    }

    fun stop() {
        transport?.close()
        transport = null
        file = null
    }

    suspend fun state(): ModelStateDto = mutex.withLock {
        decode(read().orEmpty())
    }

    suspend fun favorite(update: ModelFavoriteUpdateDto): ModelStateDto = mutex.withLock {
        val state = decode(read().orEmpty())
        val key = update.providerID to update.modelID
        val current = state.favorite
        val exists = current.any { it.providerID to it.modelID == key }
        val next = when (update.action) {
            "add" -> if (exists) current else listOf(ModelSelectionDto(update.providerID, update.modelID)) + current
            "remove" -> current.filterNot { it.providerID to it.modelID == key }
            else -> current
        }
        val updated = state.copy(favorite = next)
        write(json.encodeToString(ModelStateDto.serializer(), updated))
        updated
    }

    suspend fun selection(update: ModelSelectionUpdateDto): ModelStateDto = mutex.withLock {
        val state = decode(read().orEmpty())
        val next = state.model + (update.agent to ModelSelectionDto(update.providerID, update.modelID))
        val updated = state.copy(model = next)
        write(json.encodeToString(ModelStateDto.serializer(), updated))
        updated
    }

    suspend fun clear(agent: String): ModelStateDto = mutex.withLock {
        val state = decode(read().orEmpty())
        val updated = state.copy(model = state.model - agent)
        write(json.encodeToString(ModelStateDto.serializer(), updated))
        updated
    }

    suspend fun variant(update: ModelVariantUpdateDto): ModelStateDto = mutex.withLock {
        val state = decode(read().orEmpty())
        val updated = state.copy(variant = state.variant + (update.key to update.value))
        write(json.encodeToString(ModelStateDto.serializer(), updated))
        updated
    }

    private fun decode(raw: String): ModelStateDto =
        if (raw.isBlank()) ModelStateDto() else json.decodeFromString(ModelStateDto.serializer(), raw)

    private fun read(): String? {
        val path = file ?: return null
        if (!path.exists()) return null
        return try {
            path.readText()
        } catch (e: Exception) {
            log.warn("model state read failed: ${e.message}")
            null
        }
    }

    private fun write(raw: String) {
        val path = file ?: return
        path.parent?.createDirectories()
        path.writeText(raw)
    }

    private suspend fun resolve(): Path? {
        file?.let { return it }
        val t = transport ?: return null
        return try {
            val raw = t.call("GET", "/path")
            val dir = json.decodeFromString(PathStateDto.serializer(), raw).path
                ?.let(Path::of) ?: DEFAULT_DIR
            dir.createDirectories()
            dir.resolve("model.json").also { file = it }
        } catch (e: Exception) {
            log.warn("path fetch failed: ${e.message}")
            DEFAULT_DIR.createDirectories()
            DEFAULT_DIR.resolve("model.json").also { file = it }
        }
    }
}
