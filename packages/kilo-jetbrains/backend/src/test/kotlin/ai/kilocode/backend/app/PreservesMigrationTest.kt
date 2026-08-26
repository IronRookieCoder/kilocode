package ai.kilocode.backend.app

import ai.kilocode.backend.migration.LegacyMigrationDetection
import ai.kilocode.connection.ConnectionState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreservesMigrationTest {

    private val migrating = KiloAppState.MigrationRequired(detection())

    @Test
    fun `reconnect churn is ignored while the migration wizard is up`() {
        assertTrue(preservesMigration(migrating, ConnectionState.Connecting))
        assertTrue(preservesMigration(migrating, ConnectionState.Connected("http://127.0.0.1:1234")))
        assertTrue(preservesMigration(migrating, ConnectionState.Error("boom")))
    }

    @Test
    fun `disconnect still applies while migrating`() {
        assertFalse(preservesMigration(migrating, ConnectionState.Disconnected))
    }

    @Test
    fun `connection transitions apply normally when not migrating`() {
        assertFalse(preservesMigration(KiloAppState.Connecting, ConnectionState.Connected("http://127.0.0.1:1234")))
        assertFalse(preservesMigration(KiloAppState.Connecting, ConnectionState.Connecting))
        assertFalse(preservesMigration(KiloAppState.Disconnected, ConnectionState.Error("boom")))
    }

    private fun detection() = LegacyMigrationDetection(
        providers = emptyList(),
        mcpServers = emptyList(),
        customModes = emptyList(),
        sessions = emptyList(),
        defaultModel = null,
        settings = null,
        hasData = true,
    )
}
