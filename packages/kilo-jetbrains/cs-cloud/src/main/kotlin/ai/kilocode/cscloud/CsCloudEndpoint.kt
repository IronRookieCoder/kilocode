package ai.kilocode.cscloud

/** The local cs-cloud control-plane endpoint and credential. */
data class CsCloudEndpoint(val base: String, val key: String?)

/** User-safe failures while discovering the local cs-cloud endpoint. */
sealed class CsCloudDiscoveryError(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class MissingUrl : CsCloudDiscoveryError("cs-cloud server URL was not found")
    class UnreadableUrl(cause: Throwable) : CsCloudDiscoveryError("cs-cloud server URL could not be read", cause)
    class MalformedUrl : CsCloudDiscoveryError("cs-cloud server URL is malformed")
    class NonLoopbackUrl : CsCloudDiscoveryError("cs-cloud server URL must point to a loopback host")
    class MalformedConfig(cause: Throwable) : CsCloudDiscoveryError("cs-cloud configuration is malformed", cause)
}
