package ai.kilocode.rpc.dto

import kotlinx.serialization.Serializable

/** Payload of the `session.status` backend event. */
@Serializable
data class SessionStatusEventDto(
    val sessionID: String,
    val status: SessionStatusDto,
)
