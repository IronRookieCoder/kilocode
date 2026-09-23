package ai.kilocode.stability

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 高保真诊断详情的凭证替换结果。 */
data class Redacted(val text: String, val changed: Boolean)

private val PEM = Regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)")
private val HEADER = Regex("(?im)(^[\\t ]*)(Proxy-Authorization|Authorization|Set-Cookie|Cookie)(\\s*:\\s*)[^\\r\\n]*")
private val URL = Regex("(?i)((?:https?|ssh)://)([^\\s/@:]+):([^\\s/@]+)@")
private val QUERY = Regex("(?i)([?&](?:access_token|refresh_token|api_key|api_token|token|password|client_secret)=)[^&#\\s]+")
private val KEY = Regex(
    "(?i)(?<![A-Za-z0-9_-])([\\\"']?((?:access[ _-]?token|refresh[ _-]?token|api[ _-]?(?:key|token)|token|password|client[ _-]?secret|proxy[ _-]?authorization|authorization|set[ _-]?cookie|cookie|OPENAI_API_KEY|ANTHROPIC_API_KEY|AWS_ACCESS_KEY_ID|AWS_SECRET_ACCESS_KEY|AWS_SESSION_TOKEN|AZURE_CLIENT_SECRET|GOOGLE_APPLICATION_CREDENTIALS|GH_TOKEN|GITHUB_TOKEN|KILO_SERVER_PASSWORD))[\\\"']?\\s*[:=]\\s*)",
)
private val JWT = Regex("(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)\\.([A-Za-z0-9_-]+)(?![A-Za-z0-9_-])")

/**
 * 诊断详情的预分片凭证过滤器。顺序先移除可跨行的私钥和完整头值，再扫描结构化键值，
 * 防止秘密落在分片边界或JSON转义引号之后。调用方遇到异常必须拒绝详情草稿。
 */
object DiagnosticRedactor {
    fun clean(text: String): Redacted {
        val out = jwt(values(QUERY.replace(url(headers(PEM.replace(text, "<redacted:private-key>"))), "$1<redacted:api-token>")))
        return Redacted(out, out != text)
    }

    private fun headers(text: String): String = HEADER.replace(text) { match ->
        val type = match.groupValues[2].lowercase()
        "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}<redacted:$type>"
    }

    private fun url(text: String): String = URL.replace(text) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}:<redacted:url-credential>@"
    }

    private fun values(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val match = KEY.find(text, index)
            if (match == null) {
                out.append(text, index, text.length)
                break
            }
            out.append(text, index, match.range.last + 1)
            val start = match.range.last + 1
            val end = end(text, start, match.groupValues[2])
            if (start == end) {
                index = end
                continue
            }
            out.append("<redacted:${type(match.groupValues[2])}>")
            index = end
        }
        return out.toString()
    }

    private fun end(text: String, start: Int, key: String): Int {
        if (key.contains("authorization", ignoreCase = true)) return auth(text, start)
        if (start == text.length) return start
        val quote = text[start]
        if (quote == '\"' || quote == '\'') {
            var index = start + 1
            while (index < text.length) {
                if (text[index] == '\\') {
                    index += 2
                    continue
                }
                if (text[index] == quote) return index + 1
                index += 1
            }
            return text.length
        }
        var index = start
        while (index < text.length && text[index] !in ",;}&\r\n\t ") index += 1
        while (index > start && text[index - 1].isWhitespace()) index -= 1
        return index
    }

    private fun auth(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index] !in "\r\n\t ,") index += 1
        if (text.substring(start, index).equals("digest", ignoreCase = true)) {
            while (index < text.length && text[index] !in "\r\n") index += 1
            return index
        }
        while (index < text.length && text[index].isWhitespace()) index += 1
        while (index < text.length && text[index] !in "\r\n\t ,") index += 1
        return index
    }

    private fun type(key: String): String = when {
        key.contains("password", ignoreCase = true) -> "password"
        key.contains("client", ignoreCase = true) -> "client-secret"
        key.contains("secret", ignoreCase = true) || key.startsWith("AWS_", ignoreCase = true) ||
            key.startsWith("AZURE_", ignoreCase = true) || key.startsWith("GOOGLE_", ignoreCase = true) -> "cloud-credential"
        key.contains("access", ignoreCase = true) -> "access-token"
        key.contains("refresh", ignoreCase = true) -> "refresh-token"
        key.contains("authorization", ignoreCase = true) -> key.lowercase().replace(' ', '-').replace('_', '-')
        key.contains("cookie", ignoreCase = true) -> key.lowercase().replace(' ', '-').replace('_', '-')
        else -> "api-token"
    }

    private fun jwt(text: String): String = JWT.replace(text) { match ->
        if (jose(match.groupValues[1])) "<redacted:jwt>" else match.value
    }

    private fun jose(part: String): Boolean = runCatching {
        val bytes = Base64.getUrlDecoder().decode(part)
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject["alg"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
    }.getOrDefault(false)
}
