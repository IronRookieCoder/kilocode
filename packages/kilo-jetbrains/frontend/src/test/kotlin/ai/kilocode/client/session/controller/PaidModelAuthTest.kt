package ai.kilocode.client.session.controller

import ai.kilocode.rpc.dto.MessageErrorDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for [isPaidModelAuthRequired].
 * No IntelliJ platform setup needed — the function is entirely pure.
 */
class PaidModelAuthTest {

    private fun error(
        type: String = "APIError",
        statusCode: Int? = 401,
        responseBody: String? = """{"error":{"code":"PAID_MODEL_AUTH_REQUIRED"}}""",
    ) = MessageErrorDto(type = type, statusCode = statusCode, responseBody = responseBody)

    @Test
    fun `null error returns false`() {
        assertFalse(isPaidModelAuthRequired(null))
    }

    @Test
    fun `wrong type returns false`() {
        assertFalse(isPaidModelAuthRequired(error(type = "NetworkError")))
    }

    @Test
    fun `missing status code returns false`() {
        assertFalse(isPaidModelAuthRequired(error(statusCode = null)))
    }

    @Test
    fun `wrong status code returns false`() {
        assertFalse(isPaidModelAuthRequired(error(statusCode = 403)))
    }

    @Test
    fun `missing response body returns false`() {
        assertFalse(isPaidModelAuthRequired(error(responseBody = null)))
    }

    @Test
    fun `malformed response body returns false`() {
        assertFalse(isPaidModelAuthRequired(error(responseBody = "not json {")))
    }

    @Test
    fun `nested error code returns true`() {
        assertTrue(isPaidModelAuthRequired(error(responseBody = """{"error":{"code":"PAID_MODEL_AUTH_REQUIRED"}}""")))
    }

    @Test
    fun `top level code returns true`() {
        assertTrue(isPaidModelAuthRequired(error(responseBody = """{"code":"PAID_MODEL_AUTH_REQUIRED"}""")))
    }

    @Test
    fun `unknown code returns false`() {
        assertFalse(isPaidModelAuthRequired(error(responseBody = """{"error":{"code":"SOME_OTHER_ERROR"}}""")))
    }

    @Test
    fun `response body with extra unknown fields still returns true`() {
        assertTrue(
            isPaidModelAuthRequired(
                error(responseBody = """{"requestId":"abc","error":{"code":"PAID_MODEL_AUTH_REQUIRED","message":"Login required"}}"""),
            ),
        )
    }

    @Test
    fun `empty json object returns false`() {
        assertFalse(isPaidModelAuthRequired(error(responseBody = "{}")))
    }

    @Test
    fun `nested code does not match wrong value`() {
        assertFalse(
            isPaidModelAuthRequired(
                error(responseBody = """{"error":{"code":"UNAUTHORIZED"}}"""),
            ),
        )
    }

    // ------ isCsCloudAuthRequired ------

    private fun csCloudError(message: String? = null, type: String = "APIError", responseBody: String? = null) =
        MessageErrorDto(type = type, statusCode = 401, responseBody = responseBody, message = message)

    @Test
    fun `null cs cloud error returns false`() {
        assertFalse(isCsCloudAuthRequired(null))
    }

    @Test
    fun `not logged in message returns true`() {
        assertTrue(isCsCloudAuthRequired(csCloudError(message = "Not logged in · Please run /login")))
    }

    @Test
    fun `run login message returns true regardless of case`() {
        assertTrue(isCsCloudAuthRequired(csCloudError(message = "NOT LOGGED IN · PLEASE RUN /LOGIN")))
    }

    @Test
    fun `authentication_failed type returns true`() {
        assertTrue(isCsCloudAuthRequired(csCloudError(type = "authentication_failed")))
    }

    @Test
    fun `authentication_failed in response body returns true`() {
        assertTrue(
            isCsCloudAuthRequired(
                csCloudError(responseBody = """{"error":"authentication_failed","content":"Not logged in · Please run /login"}"""),
            ),
        )
    }

    @Test
    fun `unrelated error returns false`() {
        assertFalse(isCsCloudAuthRequired(csCloudError(message = "The model provider is rate limiting you")))
    }

    @Test
    fun `paid model auth error is not a cs cloud auth error`() {
        assertFalse(isCsCloudAuthRequired(csCloudError(responseBody = """{"error":{"code":"PAID_MODEL_AUTH_REQUIRED"}}""")))
    }

    // ------ isCsCloudAuthRequiredText ------

    @Test
    fun `null cs cloud text returns false`() {
        assertFalse(isCsCloudAuthRequiredText(null))
    }

    @Test
    fun `not logged in text returns true`() {
        assertTrue(isCsCloudAuthRequiredText("Not logged in · Please run /login"))
    }

    @Test
    fun `run login text returns true regardless of case`() {
        assertTrue(isCsCloudAuthRequiredText("NOT LOGGED IN · PLEASE RUN /LOGIN"))
    }

    @Test
    fun `authentication_failed text returns true`() {
        assertTrue(isCsCloudAuthRequiredText("authentication_failed"))
    }

    @Test
    fun `unrelated assistant text returns false`() {
        assertFalse(isCsCloudAuthRequiredText("The model provider is rate limiting you"))
    }
}
