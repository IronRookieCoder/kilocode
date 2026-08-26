package ai.kilocode.cscloud

/**
 * A normalized control-plane event, structurally identical to the app-layer
 * `SseEvent` so consumers can handle cs-cloud events without adaptation.
 */
data class SseEvent(val type: String, val data: String)
