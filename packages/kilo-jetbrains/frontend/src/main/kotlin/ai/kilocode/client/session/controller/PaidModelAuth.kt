package ai.kilocode.client.session.controller

import ai.kilocode.rpc.dto.MessageErrorDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val PAID_MODEL_AUTH_REQUIRED = "PAID_MODEL_AUTH_REQUIRED"
private val json = Json { ignoreUnknownKeys = true }

/**
 * Returns true when [error] signals that the user must sign in to use a paid model.
 *
 * Conditions (all must hold):
 * - error type is "APIError"
 * - statusCode is 401
 * - response body contains `error.code` or `code` equal to "PAID_MODEL_AUTH_REQUIRED"
 *
 * Malformed or missing response body returns false rather than throwing.
 */
internal fun isPaidModelAuthRequired(error: MessageErrorDto?): Boolean {
    if (error == null) return false
    if (error.type != "APIError") return false
    if (error.statusCode != 401) return false
    val body = error.responseBody ?: return false
    return runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val nested = obj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
        val top = obj["code"]?.jsonPrimitive?.content
        nested == PAID_MODEL_AUTH_REQUIRED || top == PAID_MODEL_AUTH_REQUIRED
    }.getOrNull() == true
}

/**
 * Returns true when [error] signals that the CoStrict cs-cloud backend needs the user
 * to sign in (auth.json missing or expired).
 *
 * The CoStrict serve backend reports this as `authentication_failed` whose message is
 * "Not logged in · Please run /login". Neither matches the Kilo gateway's paid-model
 * contract, so without this check the text would surface as a generic session error
 * with no login action.
 */
internal fun isCsCloudAuthRequired(error: MessageErrorDto?): Boolean {
    if (error == null) return false
    if (error.type == "authentication_failed") return true
    val text = buildString {
        error.message?.let { append(it).append('\n') }
        error.type?.let { append(it).append('\n') }
        error.responseBody?.let { append(it) }
    }
    return isCsCloudAuthRequiredText(text)
}

/**
 * Text-only variant for paths where the backend only delivers the assistant message
 * text. The cs-cloud daemon reports an authentication failure as a text part plus a
 * `session.result` (isError=true) event with no `session.error` payload, so the
 * MessageErrorDto-based check can never fire for it.
 */
internal fun isCsCloudAuthRequiredText(text: String?): Boolean {
    if (text == null) return false
    val value = text.lowercase()
    return value.contains("not logged in") || value.contains("run /login") || value.contains("authentication_failed")
}
