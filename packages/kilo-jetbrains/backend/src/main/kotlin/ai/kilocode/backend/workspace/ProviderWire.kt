package ai.kilocode.backend.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tolerant decoding of the `/provider` wire format into [ProviderData].
 *
 * The response nests capability flags under `capabilities`, prices under
 * `cost`, and mixes camelCase and snake_case keys, so the DTOs cannot be
 * decoded directly with kotlinx.serialization.
 */
internal object ProviderWire {

    private val json = Json { ignoreUnknownKeys = true }

    private val EFFORT_ORDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        .withIndex().associate { it.value to it.index }

    fun parse(raw: String): ProviderData {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return ProviderData(emptyList(), emptyList(), emptyMap())
        return ProviderData(
            providers = obj["all"]?.jsonArray?.map { provider(it.jsonObject) } ?: emptyList(),
            connected = obj["connected"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            defaults = obj["default"]?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content } ?: emptyMap(),
        )
    }

    private fun provider(obj: JsonObject) = ProviderInfo(
        id = obj.str("id") ?: "",
        name = obj.str("name") ?: "",
        source = obj.str("source"),
        models = obj["models"]?.jsonObject?.mapValues { (id, v) -> model(id, v.jsonObject) } ?: emptyMap(),
    )

    private fun model(id: String, obj: JsonObject): ModelInfo {
        val cap = obj["capabilities"].obj()
        val limit = obj["limit"].obj()
        val input = cap?.get("input").obj()
        return ModelInfo(
            id = obj.str("id") ?: id,
            name = obj.str("name") ?: id,
            inputPrice = obj.num("inputPrice"),
            outputPrice = obj.num("outputPrice"),
            contextLength = obj.long("contextLength"),
            releaseDate = obj.str("release_date"),
            latest = null,
            attachment = cap.bool("attachment"),
            reasoning = cap.bool("reasoning"),
            temperature = cap.bool("temperature"),
            toolCall = cap.bool("toolcall"),
            free = obj.bool("isFree"),
            byok = obj.bool("hasUserByokAvailable"),
            status = obj.str("status"),
            recommendedIndex = obj.num("recommendedIndex"),
            variants = variants(obj),
            limit = limit?.let {
                ModelLimitInfo(
                    context = it.long("context") ?: 0,
                    input = it.long("input"),
                    output = it.long("output") ?: 0,
                )
            },
            cost = cost(obj["cost"]),
            capabilities = ModelCapabilitiesInfo(
                reasoning = cap.bool("reasoning"),
                input = input?.let {
                    ModelInputCapabilitiesInfo(
                        text = it.bool("text"),
                        image = it.bool("image"),
                        audio = it.bool("audio"),
                        video = it.bool("video"),
                        pdf = it.bool("pdf"),
                    )
                },
            ).takeUnless { !it.reasoning && it.input == null },
            options = obj["options"].obj()?.str("description")?.let { ModelOptionsInfo(it) },
            autoRouting = obj["autoRouting"].obj()?.get("models")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.let { ModelAutoRoutingInfo(it) },
            terminalBench = obj["terminalBench"].obj()?.let {
                val score = it.num("overallScore") ?: return@let null
                val avg = it.num("avgAttemptCostUsd") ?: return@let null
                ModelTerminalBenchInfo(score, avg)
            },
            mayTrainOnYourPrompts = obj.bool("mayTrainOnYourPrompts"),
        )
    }

    private fun cost(raw: JsonElement?): ModelCostInfo? {
        val obj = raw.obj() ?: return null
        val input = obj.num("input") ?: return null
        val output = obj.num("output") ?: return null
        val cache = obj["cache"].obj()?.let { cache ->
            val read = cache.num("read") ?: return@let null
            val write = cache.num("write") ?: return@let null
            ModelCacheCostInfo(read, write)
        }
        return ModelCostInfo(input, output, cache)
    }

    private fun variants(obj: JsonObject): List<String> {
        val keys = obj["variants"]?.jsonObject?.keys?.toList() ?: return emptyList()
        return keys.sortedWith(compareBy<String> { EFFORT_ORDER[it] ?: Int.MAX_VALUE }.thenBy { it })
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

    private fun JsonObject?.str(name: String): String? =
        runCatching { this?.get(name)?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject?.bool(name: String): Boolean =
        runCatching { this?.get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull() ?: false

    private fun JsonObject?.num(name: String): Double? = runCatching {
        when (val prim = this?.get(name)?.jsonPrimitive) {
            null -> null
            else -> prim.contentOrNull?.toDoubleOrNull()
        }
    }.getOrNull()

    private fun JsonObject?.long(name: String): Long? =
        runCatching { this?.get(name)?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.getOrNull()
}
