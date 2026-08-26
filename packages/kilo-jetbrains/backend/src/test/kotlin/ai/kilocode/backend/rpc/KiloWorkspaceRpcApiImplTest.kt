package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.backend.testing.FakeCliServer
import ai.kilocode.backend.testing.MockCliServer
import ai.kilocode.backend.testing.TestLog
import ai.kilocode.rpc.dto.WorkspaceFileDto
import ai.kilocode.rpc.dto.KiloWorkspaceStatusDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KiloWorkspaceRpcApiImplTest {
    private val mock = MockCliServer()
    private val log = TestLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val apps = mutableListOf<KiloBackendAppService>()

    @AfterTest
    fun tearDown() = runBlocking {
        apps.forEach { it.dispose() }
        apps.clear()
        scope.cancel()
        mock.close()
    }

    @Test
    fun `searches files and directories through core`() = runBlocking {
        mock.findFiles = """["src/Main.kt",".kilo/worktrees/hidden.kt"]"""
        mock.findDirectories = """["src/","docs/"]"""
        val dir = Files.createTempDirectory("kilo-search")
        try {
            val app = app()

            val result = KiloWorkspaceRpcApiImpl(app).searchFiles(dir.toString(), "src", 3)

            assertEquals(
                listOf(
                    WorkspaceFileDto("src", "src", directory = true),
                    WorkspaceFileDto("docs", "docs", directory = true),
                    WorkspaceFileDto("src/Main.kt", "Main.kt"),
                ),
                result.files,
            )
            assertEquals(2, mock.requestCount("/find/file"))
            assertTrue(mock.findFilePaths.any { it.contains("type=file") && it.contains("query=src") })
            assertTrue(mock.findFilePaths.any { it.contains("type=directory") && it.contains("query=src") })
        } finally {
            delete(dir)
        }
    }

    @Test
    fun `models loads costrict providers through the connection base`() = runBlocking {
        mock.providers = """
            {
                "all": [
                    {
                        "id": "costrict",
                        "name": "CoStrict",
                        "source": "config",
                        "default_model": "Auto",
                        "models": {
                            "Auto": {"id": "Auto", "name": "Auto", "status": "active"},
                            "DeepSeek-V4-Flash": {"id": "DeepSeek-V4-Flash", "name": "DeepSeek-V4-Flash", "status": "active"}
                        }
                    }
                ],
                "default": {"build": "costrict/Auto"},
                "connected": ["costrict"],
                "failed": []
            }
        """.trimIndent()
        mock.agents = """[{"name":"code","displayName":"Code","mode":"primary","permission":[],"options":{}}]"""
        val dir = Files.createTempDirectory("kilo-models")
        try {
            val app = app()
            val result = KiloWorkspaceRpcApiImpl(app).models(dir.toString())

            assertEquals(emptyList(), result.errors)
            val provider = assertNotNull(result.providers?.providers?.single { it.id == "costrict" })
            assertEquals("CoStrict", provider.name)
            assertEquals(setOf("Auto", "DeepSeek-V4-Flash"), provider.models.keys)
            assertEquals(listOf("costrict"), result.providers?.connected)
            assertEquals(mapOf("build" to "costrict/Auto"), result.providers?.defaults)
        } finally {
            delete(dir)
        }
    }

    @Test
    fun `state maps unsupported workspace`() = runBlocking {
        val app = app()
        val rpc = KiloWorkspaceRpcApiImpl(app)

        val state = withTimeoutOrNull(15_000) {
            rpc.state("/${'$'}devcontainer.ij/abc@u~run~user~1001~podman~podman.sock/workspaces/project")
                .first { it.status == KiloWorkspaceStatusDto.UNSUPPORTED }
        }

        assertNotNull(state)
        assertEquals(KiloWorkspaceStatusDto.UNSUPPORTED, state.status)
        assertEquals("devcontainer_virtual_filesystem", state.error)
    }

    private suspend fun app(): KiloBackendAppService {
        val app = KiloBackendAppService.create(scope, FakeCliServer(mock), log).also { apps.add(it) }
        app.connect()
        val state = assertNotNull(
            withTimeoutOrNull(35_000) {
                app.appState.first {
                    it is KiloAppState.Ready || it is KiloAppState.Error || it is KiloAppState.MigrationRequired
                }
            },
            "App startup timed out in ${app.appState.value}; logs=${log.messages}",
        )
        assertIs<KiloAppState.Ready>(state, "App startup failed; logs=${log.messages}")
        return app
    }

    private fun delete(dir: java.nio.file.Path) {
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
