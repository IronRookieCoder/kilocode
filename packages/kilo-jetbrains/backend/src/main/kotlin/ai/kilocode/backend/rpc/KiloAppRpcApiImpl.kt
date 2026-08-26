@file:Suppress("UnstableApiUsage")

package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.backend.telemetry.KiloBackendTelemetry
import ai.kilocode.backend.app.LoadError
import ai.kilocode.backend.app.LoadProgress
import ai.kilocode.backend.app.ProfileResult
import ai.kilocode.log.KiloLog
import ai.kilocode.log.LogConfig
import ai.kilocode.rpc.dto.ConfigPatchDto
import ai.kilocode.rpc.KiloAppRpcApi
import ai.kilocode.rpc.dto.ConfigWarningDto
import ai.kilocode.rpc.dto.DeviceAuthDto
import ai.kilocode.rpc.dto.HealthDto
import ai.kilocode.rpc.dto.KiloAppStateDto
import ai.kilocode.rpc.dto.KiloAppStatusDto
import ai.kilocode.rpc.dto.LoadErrorDto
import ai.kilocode.rpc.dto.LoadProgressDto
import ai.kilocode.rpc.dto.LogConfigDto
import ai.kilocode.rpc.dto.LogFileDto
import ai.kilocode.rpc.dto.ModelFavoriteUpdateDto
import ai.kilocode.rpc.dto.ModelSelectionUpdateDto
import ai.kilocode.rpc.dto.ModelStateDto
import ai.kilocode.rpc.dto.ModelVariantUpdateDto
import ai.kilocode.rpc.dto.ProfileBalanceDto
import ai.kilocode.rpc.dto.ProfileDto
import ai.kilocode.rpc.dto.ProfileKiloPassDto
import ai.kilocode.rpc.dto.ProfileOrganizationDto
import ai.kilocode.rpc.dto.ProfileStatusDto
import ai.kilocode.rpc.dto.TelemetryCaptureDto
import com.intellij.openapi.components.service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * Backend implementation of [KiloAppRpcApi].
 *
 * Delegates directly to the app-level [KiloBackendAppService] —
 * no project resolution needed since all operations are app-scoped.
 */
class KiloAppRpcApiImpl : KiloAppRpcApi {

    private val app: KiloBackendAppService get() = service()

    override suspend fun connect() = app.connect()

    override suspend fun state(): Flow<KiloAppStateDto> =
        app.appState.map(::dto).distinctUntilChanged()

    override suspend fun health(): HealthDto = app.health()

    override suspend fun cliVersion(): String = "0.0.0" // transitional until cs-cloud connector supplies build info

    override suspend fun cliPlatform(): String = com.intellij.openapi.util.SystemInfo.OS_NAME

    override suspend fun cliBundled(): Boolean = false

    override suspend fun retry() = app.retry()

    override suspend fun restart() = app.restart()

    override suspend fun reinstall() = app.reinstall()

    override suspend fun modelState(): ModelStateDto {
        app.requireReady()
        return app.models.state()
    }

    override suspend fun updateModelFavorite(update: ModelFavoriteUpdateDto): ModelStateDto {
        app.requireReady()
        return app.models.favorite(update)
    }

    override suspend fun updateModelSelection(update: ModelSelectionUpdateDto): ModelStateDto {
        app.requireReady()
        return app.models.selection(update)
    }

    override suspend fun clearModelSelection(agent: String): ModelStateDto {
        app.requireReady()
        return app.models.clear(agent)
    }

    override suspend fun updateModelVariant(update: ModelVariantUpdateDto): ModelStateDto {
        app.requireReady()
        return app.models.variant(update)
    }

    override suspend fun updateConfig(patch: ConfigPatchDto): KiloAppStateDto {
        app.requireReady()
        return appStateDto(app.updateConfig(patch))
    }

    override suspend fun applyLogConfig(config: LogConfigDto) {
        LogConfig.apply(config.level, config.contentMode, config.previewMax)
    }

    override suspend fun backendLogFile(): LogFileDto? = withContext(Dispatchers.IO) {
        val path = KiloLog.logFile()
        if (!Files.exists(path)) return@withContext null
        LogFileDto(path.fileName.toString(), Files.readString(path))
    }

    override suspend fun refreshProfile(): ProfileDto? = app.refreshProfile()

    override suspend fun startLogin(directory: String?): DeviceAuthDto = app.startLogin(directory)

    override suspend fun completeLogin(directory: String?): ProfileDto? = app.completeLogin(directory)

    override suspend fun logout(): Boolean = app.logout()

    override suspend fun setOrganization(organizationId: String?): ProfileDto? =
        app.setOrganization(organizationId)

    override suspend fun captureTelemetry(capture: TelemetryCaptureDto) {
        service<KiloBackendTelemetry>().capture(app.transportFactory()?.create(), capture.event, capture.properties)
    }

    private fun dto(state: KiloAppState): KiloAppStateDto =
        appStateDto(state)
}

internal fun appStateDto(state: KiloAppState): KiloAppStateDto =
    when (state) {
        KiloAppState.Disconnected -> KiloAppStateDto(KiloAppStatusDto.DISCONNECTED)
        KiloAppState.Connecting -> KiloAppStateDto(KiloAppStatusDto.CONNECTING)
        is KiloAppState.Loading -> KiloAppStateDto(
            status = KiloAppStatusDto.LOADING,
            progress = progress(state.progress),
        )
        is KiloAppState.MigrationRequired -> KiloAppStateDto(
            status = KiloAppStatusDto.MIGRATION_REQUIRED,
            migration = MigrationRpcMapper.toDto(state.detection),
        )
        is KiloAppState.Ready -> KiloAppStateDto(
            status = KiloAppStatusDto.READY,
            progress = LoadProgressDto(
                config = true,
                notifications = true,
                profile = if (state.data.profile != null) ProfileStatusDto.LOADED
                    else ProfileStatusDto.NOT_LOGGED_IN,
            ),
            warnings = state.data.warnings,
            config = state.data.config,
            profile = state.data.profile,
        )
        is KiloAppState.Error -> KiloAppStateDto(
            status = KiloAppStatusDto.ERROR,
            error = state.message,
            errors = state.errors.map(::error),
        )
    }


private fun progress(p: LoadProgress) = LoadProgressDto(
    config = p.config,
    notifications = false,
    profile = when (p.profile) {
        ProfileResult.PENDING -> ProfileStatusDto.PENDING
        ProfileResult.LOADED -> ProfileStatusDto.LOADED
        ProfileResult.NOT_LOGGED_IN -> ProfileStatusDto.NOT_LOGGED_IN
    },
)

private fun error(e: LoadError) = LoadErrorDto(
    resource = e.resource,
    status = e.status,
    detail = e.detail,
)

