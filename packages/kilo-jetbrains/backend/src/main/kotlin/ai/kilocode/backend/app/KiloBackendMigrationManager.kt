package ai.kilocode.backend.app

import ai.kilocode.backend.migration.LegacyCleanupReport
import ai.kilocode.backend.migration.LegacyCleanupTargets
import ai.kilocode.backend.migration.LegacyMigrationBackend
import ai.kilocode.backend.migration.LegacyMigrationDetection
import ai.kilocode.backend.migration.LegacyMigrationEngine
import ai.kilocode.backend.migration.LegacyMigrationReport
import ai.kilocode.backend.migration.LegacyMigrationSelections
import ai.kilocode.backend.migration.LegacyMigrationSink
import ai.kilocode.backend.migration.LegacyMigrationStatus
import ai.kilocode.backend.migration.LegacyMigrationStore
import ai.kilocode.backend.migration.LegacyMigrationTransportBackend
import ai.kilocode.connection.Transport

/**
 * Thin factory/wrapper that creates [LegacyMigrationEngine] instances using the active
 * backend connection. Does not auto-run migration and does not touch any UI.
 *
 * Instantiate when the connection is ready (transport factory available).
 * The [store] is caller-supplied, allowing test and UI flows to provide different adapters.
 */
class KiloBackendMigrationManager(
    private val transportFactory: () -> Transport,
) {
    private fun backend(): LegacyMigrationBackend = LegacyMigrationTransportBackend(transportFactory())

    fun status(store: LegacyMigrationStore): LegacyMigrationStatus? =
        LegacyMigrationEngine(store, backend()).status()

    fun mark(store: LegacyMigrationStore, status: LegacyMigrationStatus) =
        LegacyMigrationEngine(store, backend()).mark(status)

    fun detect(store: LegacyMigrationStore): LegacyMigrationDetection =
        LegacyMigrationEngine(store, backend()).detect()

    fun migrate(
        store: LegacyMigrationStore,
        selections: LegacyMigrationSelections,
        sink: LegacyMigrationSink = LegacyMigrationSink.None,
    ): LegacyMigrationReport =
        LegacyMigrationEngine(store, backend()).migrate(selections, sink)

    fun cleanup(store: LegacyMigrationStore, targets: LegacyCleanupTargets): LegacyCleanupReport =
        LegacyMigrationEngine(store, backend()).cleanup(targets)
}
