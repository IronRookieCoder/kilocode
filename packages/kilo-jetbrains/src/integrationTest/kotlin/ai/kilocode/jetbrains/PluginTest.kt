package ai.kilocode.jetbrains

import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.ide.installer.IdeInstallerFactory
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.Test
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Path

/**
 * Integration tests that start a real IDE with the built plugin installed
 * (https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html).
 *
 * The Gradle `integrationTest` task (intellijPlatformTesting.testIdeUi) provides the plugin
 * distribution built by `buildPlugin` through the `path.to.build.plugin` system property.
 */
class PluginTest {

    init {
        // Integration tests span two processes; exceptions inside the IDE process are not
        // propagated to the test process automatically. The Starter framework collects them
        // from the IDE's MessageBus and reports them through CIServer, so override the default
        // no-op implementation to turn IDE-side exceptions into test failures.
        di = DI {
            extend(di)
            // Gradle points `kilo.integrationTest.ideHome` at a locally installed IDE matching the
            // plugin's platform (the verifyPlugin cache under .intellijPlatform/ides) so tests don't
            // depend on downloading IDE releases; without the property the public download is used.
            val localIdeHome = System.getProperty("kilo.integrationTest.ideHome")
            if (!localIdeHome.isNullOrBlank()) {
                bindSingleton<IdeInstallerFactory>(overrides = true) {
                    object : IdeInstallerFactory() {
                        override fun createInstaller(ideInfo: IdeInfo, downloader: IdeDownloader): IdeInstaller =
                            ExistingIdeInstaller(Path.of(localIdeHome))
                    }
                }
            }
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                    ) {
                        // JUnit 5.13 made all Assertions.fail overloads generic, which Kotlin cannot
                        // infer in a Unit context; throwing AssertionError is the equivalent.
                        throw AssertionError("$testName fails: $message. \n$details")
                    }
                }
            }
        }
    }

    @Test
    fun `plugin installs and IDE starts without exceptions`() {
        // No withVersion()/useRelease() here: those hardcode `getInstaller` to the public
        // release downloader and would bypass the ExistingIdeInstaller binding above.
        // Without a version the default installer resolves through DI (local IDE when
        // provided, otherwise the latest public release). IU matches the product code of
        // the verifyPlugin-cached IDE under .intellijPlatform/ides.
        Starter.newContext(
            testName = "costrictPluginSmoke",
            TestCase(IdeProductProvider.IU, projectInfo = NoProject),
        ).apply {
            val pathToBuildPlugin = requireNotNull(System.getProperty("path.to.build.plugin")) {
                "path.to.build.plugin is not set; run integration tests via the Gradle integrationTest task"
            }
            PluginConfigurator(this).installPluginFromPath(Path.of(pathToBuildPlugin))
        }.runIdeWithDriver().useDriverAndCloseIde {
            // Empty on purpose: reaching this block means the IDE started and the Driver
            // connected. Any IDE-process exception fails the test via the CIServer override.
        }
    }
}
