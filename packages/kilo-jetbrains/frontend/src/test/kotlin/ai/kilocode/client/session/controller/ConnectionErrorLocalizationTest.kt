package ai.kilocode.client.session.controller

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.rpc.ConnectionErrorCode
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.KiloWorkspaceStateDto
import ai.kilocode.rpc.dto.KiloWorkspaceStatusDto
import ai.kilocode.rpc.dto.LoadErrorDto

/**
 * P2-2: app errors carrying a stable cs-cloud code must surface a localized summary and
 * actionable guidance while keeping the raw diagnostic underneath, and unknown or missing
 * codes must keep the generic banner.
 */
class ConnectionErrorLocalizationTest : SessionControllerTestBase() {

    fun `test each cs-cloud error code shows localized summary and guidance`() {
        for (code in listOf(
            ConnectionErrorCode.CSC_NOT_INSTALLED,
            ConnectionErrorCode.DAEMON_DOWN,
            ConnectionErrorCode.UNAUTHORIZED,
            ConnectionErrorCode.NPM_NOT_FOUND,
        )) {
            val event = showError(
                KiloAppStateDto(
                    status = KiloAppStatusDto.ERROR,
                    errors = listOf(LoadErrorDto(resource = "connection", detail = "connection refused", code = code)),
                ),
            )

            assertEquals(KiloBundle.message("csCloud.error.$code.title"), event.summary)
            assertEquals(
                KiloBundle.message("csCloud.error.$code.desc") + "\n\nconnection refused",
                event.detail,
            )
            assertEquals(code, event.code)
            assertEquals("app", event.source)
        }
    }

    fun `test localized detail keeps the prefixed diagnostic of non-connection resources`() {
        val event = showError(
            KiloAppStateDto(
                status = KiloAppStatusDto.ERROR,
                errors = listOf(
                    LoadErrorDto(resource = "config", detail = "HTTP 500: broken", code = ConnectionErrorCode.DAEMON_DOWN),
                ),
            ),
        )

        assertEquals(
            KiloBundle.message("csCloud.error.daemon_down.desc") + "\n\nconfig: HTTP 500: broken",
            event.detail,
        )
    }

    fun `test localized detail stays actionable without a diagnostic`() {
        val event = showError(
            KiloAppStateDto(
                status = KiloAppStatusDto.ERROR,
                errors = listOf(LoadErrorDto(resource = "connection", code = ConnectionErrorCode.CSC_NOT_INSTALLED)),
            ),
        )

        assertEquals(KiloBundle.message("csCloud.error.csc_not_installed.desc"), event.detail)
    }

    fun `test unknown code keeps generic banner and raw detail`() {
        val event = showError(
            KiloAppStateDto(
                status = KiloAppStatusDto.ERROR,
                errors = listOf(
                    LoadErrorDto(resource = "config", detail = "HTTP 500: broken", code = "providers_broken"),
                ),
            ),
        )

        assertEquals("Connection failed", event.summary)
        assertEquals("config: HTTP 500: broken", event.detail)
        assertEquals("providers_broken", event.code)
    }

    fun `test missing code keeps generic banner`() {
        val event = showError(
            KiloAppStateDto(
                status = KiloAppStatusDto.ERROR,
                error = "CLI startup failed",
                errors = listOf(LoadErrorDto(resource = "connection", detail = "CLI startup failed")),
            ),
        )

        assertEquals("Connection failed", event.summary)
        assertEquals("CLI startup failed", event.detail)
        assertNull(event.code)
    }

    fun `test workspace error branch is untouched`() {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY)
        projectRpc.state.value = workspaceReady()
        val m = controller(displayMs = 50)
        val events = collect(m)
        flush()
        events.clear()

        projectRpc.state.value = KiloWorkspaceStateDto(
            status = KiloWorkspaceStatusDto.ERROR,
            error = "workspace failed",
            errors = listOf(
                LoadErrorDto(resource = "providers", detail = "bad provider json", code = ConnectionErrorCode.DAEMON_DOWN),
            ),
        )
        pause(80)

        val event = events.filterIsInstance<SessionControllerEvent.ConnectionChanged.ShowError>().single()
        assertEquals("Workspace loading failed", event.summary)
        assertEquals("providers: bad provider json", event.detail)
        assertEquals("workspace", event.source)
    }

    private fun showError(state: KiloAppStateDto): SessionControllerEvent.ConnectionChanged.ShowError {
        appRpc.state.value = KiloAppStateDto(KiloAppStatusDto.READY)
        projectRpc.state.value = workspaceReady()
        val m = controller(displayMs = 50)
        val events = collect(m)
        flush()
        events.clear()

        appRpc.state.value = state
        pause(80)

        return events.filterIsInstance<SessionControllerEvent.ConnectionChanged.ShowError>().single()
    }
}
