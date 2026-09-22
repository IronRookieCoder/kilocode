package ai.kilocode.stability

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ProtocolTransport(val wire: String) { HTTP("http"), SSE("sse"), RPC("rpc") }

enum class ProtocolStage(val wire: String) { DECODE("decode"), APPLY("apply") }

enum class ProtocolCode(val wire: String) {
    UNSUPPORTED_SCHEMA("unsupported_schema"),
    DECODE_FAILED("decode_failed"),
    APPLY_VIOLATION("apply_violation"),
    OTHER("other"),
}

fun Operations.protocolError(transport: ProtocolTransport, stage: ProtocolStage, code: ProtocolCode): Admission =
    record(
        Draft(
            "protocol.error",
            "diagnostic",
            "critical",
            buildJsonObject {
                put("transport", transport.wire)
                put("stage", stage.wire)
                put("error_code", code.wire)
            },
        ),
    )
