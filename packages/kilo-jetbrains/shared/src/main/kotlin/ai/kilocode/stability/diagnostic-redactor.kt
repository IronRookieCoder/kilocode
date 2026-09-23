package ai.kilocode.stability

/** 高保真诊断详情的凭证替换结果。 */
data class Redacted(val text: String, val changed: Boolean)

private data class Redaction(val pattern: Regex, val value: String)

/**
 * 诊断详情的预分片凭证过滤器。规则按风险从高到低固定执行，避免较窄的键值规则破坏PEM或请求头。
 * 调用方遇到异常必须拒绝详情草稿，改写为不含原文的diagnostic.redaction_failed。
 */
object DiagnosticRedactor {
    private val rules = listOf(
        Redaction(
            Regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
            "<redacted:private-key>",
        ),
        Redaction(
            Regex("(?im)(\\bProxy-Authorization\\s*:\\s*)[^\\r\\n]*"),
            "$1<redacted:proxy-authorization>",
        ),
        Redaction(
            Regex("(?im)((?<![-\\w])Authorization\\s*:\\s*)(?:Bearer\\s+)?[^\\s,;]+"),
            "$1<redacted:authorization>",
        ),
        Redaction(Regex("(?im)(\\bSet-Cookie\\s*:\\s*)[^\\r\\n]*"), "$1<redacted:set-cookie>"),
        Redaction(Regex("(?im)((?<![-\\w])Cookie\\s*:\\s*)[^\\r\\n]*"), "$1<redacted:cookie>"),
        Redaction(
            Regex("(?i)([?&](?:access_token|refresh_token|api_key|api_token|token|password|client_secret)=)[^&#\\s]+"),
            "$1<redacted:api-token>",
        ),
        Redaction(
            Regex("(?im)(\\b(?:OPENAI_API_KEY|ANTHROPIC_API_KEY|AWS_SECRET_ACCESS_KEY|AWS_SESSION_TOKEN|GH_TOKEN|GITHUB_TOKEN|KILO_SERVER_PASSWORD)\\s*=\\s*)[^\\s\\r\\n]+"),
            "$1<redacted:api-token>",
        ),
        key("access[ _-]?token", "access-token"),
        key("refresh[ _-]?token", "refresh-token"),
        key("(?:api[ _-]?(?:key|token)|token)", "api-token"),
        key("password", "password"),
        key("client[ _-]?secret", "client-secret"),
        key("proxy[ _-]?authorization", "proxy-authorization"),
        key("authorization", "authorization"),
        key("set[ _-]?cookie", "set-cookie"),
        key("cookie", "cookie"),
        Redaction(
            Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])"),
            "<redacted:jwt>",
        ),
    )

    fun clean(text: String): Redacted {
        val out = rules.fold(text) { value, rule -> rule.pattern.replace(value, rule.value) }
        return Redacted(out, out != text)
    }

    private fun key(name: String, type: String): Redaction = Redaction(
        Regex("(?i)(?<![A-Za-z0-9_-])([\\\"']?$name[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}&]+)"),
        "$1<redacted:$type>",
    )
}
