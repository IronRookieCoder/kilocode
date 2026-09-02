import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.InstrumentCodeTask
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware.PluginInstallationTarget
import java.io.File
import java.time.LocalDate

group = "ai.kilocode.jetbrains"

fun port(value: String): Int {
    val text = value.trim()
    require(text.isNotEmpty()) {
        "kilo.splitModeServerPort must be an integer from 0 to 65535; use 0 or omit it for a random port"
    }
    val n = text.toIntOrNull()
        ?: error("kilo.splitModeServerPort must be an integer from 0 to 65535; use 0 or omit it for a random port")
    require(n in 0..65535) {
        "kilo.splitModeServerPort must be an integer from 0 to 65535; use 0 or omit it for a random port"
    }
    return n
}

fun checked(value: String): String {
    if (value == "0.0.0-dev") return value
    require(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-rc\\.[0-9]+)?$").matches(value)) {
        "Invalid JetBrains plugin version: $value"
    }
    return value
}

data class Release(val major: Int, val minor: Int, val patch: Int, val rc: Int?) : Comparable<Release> {
    val stable = rc == null
    val base get() = if (stable) this else Release(major, minor, patch, null)
    val text = listOfNotNull("$major.$minor.$patch", rc?.let { "rc.$it" }).joinToString("-")

    override fun compareTo(other: Release): Int {
        val cmp = compareValuesBy(this, other, Release::major, Release::minor, Release::patch)
        if (cmp != 0) return cmp
        return compareValues(rc ?: Int.MAX_VALUE, other.rc ?: Int.MAX_VALUE)
    }
}

fun release(value: String): Release? {
    val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-rc\\.(\\d+))?$").matchEntire(value) ?: return null
    return Release(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
        match.groupValues[4].takeIf { it.isNotEmpty() }?.toInt(),
    )
}

fun releases(): List<Release> {
    val heading = Regex("^## \\[(.+?)](?: - .*)?$|^## ([^\\[]\\S*)$")
    return file("CHANGELOG.md").readLines()
        .mapNotNull { line ->
            val match = heading.matchEntire(line.trim()) ?: return@mapNotNull null
            release(match.groupValues[1].ifEmpty { match.groupValues[2] })
        }
        .distinctBy { it.text }
}

fun selected(value: String): List<String> {
    val current = release(value) ?: return emptyList()
    val entries = releases()
    val rcs = if (current.stable) emptyList() else entries
        .filter { !it.stable && it.base == current.base && it <= current }
        .sortedDescending()
    val stables = entries
        .filter { it.stable && if (current.stable) it <= current else it < current.base }
        .sortedDescending()
        .take(5)
    return (rcs + stables).map { it.text }
}

fun gitTag(): String? {
    val text = providers.exec {
        commandLine("git", "tag", "--points-at", "HEAD")
    }.standardOutput.asText.get()
    return text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("jetbrains/v") }
}

val release = providers.gradleProperty("production").map { it.toBoolean() }.orElse(false).get()
val pinned = providers.gradleProperty("kilo.cli.pinned").map { it.trim().toBoolean() }.orElse(true).get()
val runtime = providers.gradleProperty("kilo.cli.runtime").map { it.trim().toBoolean() }.orElse(true).get()
val override = providers.gradleProperty("kilo.version").orNull?.trim()?.takeIf { it.isNotEmpty() }
val prop = providers.gradleProperty("kilo.jetbrains.version").orNull?.trim()?.takeIf { it.isNotEmpty() }
val tag = gitTag()?.removePrefix("jetbrains/v")
val ver = override?.let(::checked) ?: prop?.let(::checked) ?: if (release) checked(
    tag ?: error("Missing JetBrains plugin version. Publish builds must set kilo.jetbrains.version or run from a jetbrains/v<version> tag."),
) else checked(tag ?: "0.0.0-dev")

if (release && !pinned) error(
    "kilo.cli.pinned=false is a dev-only mode and cannot be released. Set kilo.cli.pinned=true before a production/publish build."
)
if (release && !runtime) error(
    "kilo.cli.runtime=false is a dev-only mode and cannot be released. Set kilo.cli.runtime=true before a production/publish build."
)

val channel = providers.gradleProperty("kilo.channel").map { it.trim() }.orElse("default")
val splitPort = providers.gradleProperty("kilo.splitModeServerPort").map(::port).orElse(0)
val isolated = providers.gradleProperty("kilo.dev.storage.isolated").map { it.toBoolean() }.orElse(false)
val worktreeRoot = providers.gradleProperty("kilo.dev.worktree.root").orElse(
    providers.provider { rootProject.layout.projectDirectory.asFile.parentFile.parentFile.canonicalPath }
)

version = ver

plugins {
    application
    id("java")
    alias(libs.plugins.intellij.platform)
    alias(libs.plugins.detekt)
    alias(libs.plugins.changelog)

    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization) apply false
}

// The integrationTest source set hosts Starter-based integration tests that run a real IDE
// (see `intellijPlatformTesting.testIdeUi` below); Kotlin is needed to compile them.
kotlin {
    jvmToolchain(21)
}

changelog {
    version = ver
    path = file("CHANGELOG.md").canonicalPath
    header = provider { "[${version.get()}] - ${LocalDate.now()}" }
    unreleasedTerm = "[Unreleased]"
    keepUnreleasedSection = true
    repositoryUrl = "https://github.com/Kilo-Org/kilocode"
    groups = listOf("Added", "Changed", "Fixed", "Removed", "Security")
    combinePreReleases = false
}

val notes = providers.gradleProperty("kilo.changeNotes").orElse(
    provider {
        val versions = selected(ver).filter { changelog.has(it) }
        if (versions.isNotEmpty()) return@provider versions.joinToString("\n") { item ->
            changelog.renderItem(
                changelog.get(item).withHeader(true).withEmptySections(false),
                Changelog.OutputType.HTML,
            )
        }
        val item = if (changelog.has(ver)) changelog.get(ver) else changelog.getUnreleased()
        changelog.renderItem(item.withHeader(false).withEmptySections(false), Changelog.OutputType.HTML)
    },
)

subprojects {
    apply(plugin = "org.jetbrains.intellij.platform.module")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    detekt {
        config.setFrom(rootProject.file("detekt.yml"))
        buildUponDefaultConfig = true
        source.setFrom("src/main/kotlin")
    }

    tasks.withType<PrepareSandboxTask>().configureEach {
        // kotlinx-serialization ships with the IntelliJ Platform. Bundling our own copy puts the
        // classes on two classpaths, and in split mode different plugin classloaders then bind to
        // different copies — cross-module calls with KSerializer in the signature die with
        // "LinkageError: loader constraint violation" (e.g. KiloAppRpcApi#cloudFavorites).
        // Keep the platform's single copy; modules compile against the Maven artifact and tests
        // keep it on their own classpath, so only the sandbox is affected.
        exclude("kotlinx-serialization-*.jar")
    }
}

detekt {
    config.setFrom(file("detekt.yml"))
    buildUponDefaultConfig = true
    source.setFrom("src/main/kotlin")
}

allprojects {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
}

// kotlinx-serialization ships with the IntelliJ Platform; see the PrepareSandboxTask exclusion in
// `subprojects` for why the plugin must never bundle a second copy.
tasks.withType<PrepareSandboxTask>().configureEach {
    exclude("kotlinx-serialization-*.jar")
}

// Integration tests (https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html) run a
// real IDE in a separate process: the JUnit 5 test process drives it through the Starter/Driver
// frameworks while the plugin under test is installed from the `buildPlugin` ZIP.
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}
val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)

        pluginModule(implementation(project(":shared")))
        pluginModule(implementation(project(":frontend")))
        pluginModule(implementation(project(":backend")))
        pluginModule(implementation(project(":cs-cloud")))
        pluginModule(implementation(project(":cs-cloud-mcp")))
        testFramework(TestFrameworkType.Platform)
        // Starter + Driver frameworks land on `integrationTestImplementation`, not the classic test config.
        testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
    }

    integrationTestImplementation(libs.junit.jupiter)
    integrationTestImplementation(libs.kodein.di.jvm)
    integrationTestImplementation(libs.kotlinx.coroutines.core.jvm)
    // The Starter framework pulls kotlin-reflect built with the platform's Kotlin (2.3.x), but its
    // metadata does not raise kotlin-stdlib accordingly; without this, the test process initializes
    // kodein against an older stdlib and dies on missing kotlin.jvm.internal.* classes.
    integrationTestImplementation(kotlin("stdlib"))
    // Gradle 9 no longer injects the JUnit Platform launcher from its own distribution.
    // String notation: the configuration is created in this script, so no type-safe accessor exists.
    "integrationTestRuntimeOnly"(libs.junit.platform.launcher)
}

// Runs the `integrationTest` source set against a real IDE. The task automatically depends on
// `buildPlugin` and exposes the plugin ZIP to tests via the `path.to.build.plugin` system property.
val integrationTestIdeHome = providers.provider {
    // Reuse a full IDE install cached by verifyPlugin (.intellijPlatform/ides, e.g. IU-2026.1)
    // instead of downloading a release; empty value lets Starter download one instead.
    val idesDir = layout.projectDirectory.dir(".intellijPlatform/ides").asFile
    val platformVersion = libs.versions.intellij.platform.get()
    idesDir.listFiles { file: File -> file.isDirectory && file.name.endsWith(platformVersion) }
        ?.maxByOrNull { it.name }
        ?.absolutePath
        .orEmpty()
}

val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        // Not a Provider: Test.systemProperty does not unpack providers, it would stringify them.
        systemProperty("kilo.integrationTest.ideHome", integrationTestIdeHome.getOrElse(""))
    }
}

intellijPlatform {
    splitMode = true
    pluginInstallationTarget = PluginInstallationTarget.BOTH

    pluginConfiguration {
        id = "ai.kilocode.jetbrains"
        name = "Costrict"
        version = provider { ver }
        changeNotes = notes

        ideaVersion {
            untilBuild = provider { null }
        }

        vendor {
            name = "Costrict"
            url = "https://costrict.ai"
        }
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
        channels = channel.map { value ->
            if (value.isBlank() || value == "default") return@map listOf("default")
            listOf(value)
        }
    }

    signing {
        // CI passes raw secret content so signing can run without writing secrets to disk.
        // Local release builds can still point these properties at pre-existing secret files.
        certificateChain = providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("JETBRAINS_PRIVATE_KEY")
        certificateChainFile.fileProvider(providers.environmentVariable("JETBRAINS_CERTIFICATE_CHAIN_FILE").map { File(it) })
        privateKeyFile.fileProvider(providers.environmentVariable("JETBRAINS_PRIVATE_KEY_FILE").map { File(it) })
        password = providers.environmentVariable("JETBRAINS_PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, libs.versions.intellij.platform)
        }
    }
}

tasks {
    named("verifyPluginSignature") {
        dependsOn("signPlugin")
    }

    withType<InstrumentCodeTask> {
        enabled = false
    }

    runIdeBackend {
        splitModeServerPort.set(splitPort)
        dependsOn(":backend:processResources")
    }

    runIdeFrontend {
        splitModeServerPort.set(splitPort)
    }

    runIdeSplitMode {
        splitModeServerPort.set(splitPort)
        dependsOn(":backend:processResources")
    }
}

// Compile-only typecheck: verifies Kotlin compiles (including generated API client)
// without running buildPlugin.
tasks.register("typecheck") {
    dependsOn(
        ":shared:compileKotlin",
        ":frontend:compileKotlin",
        ":backend:compileKotlin",
        ":cs-cloud:compileKotlin",
        ":cs-cloud-mcp:compileKotlin",
        ":frontend:compileTestKotlin",
        ":backend:compileTestKotlin",
        ":cs-cloud:compileTestKotlin",
        ":cs-cloud-mcp:compileTestKotlin",
    )
}

tasks.named<JavaExec>("runIde") {
    dependsOn(":backend:processResources")
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dnosplash=true")
    }
}

tasks.withType<RunIdeTask> {
    val level = providers.gradleProperty("kilo.dev.log.level").orNull ?: "DEBUG"
    val content = providers.gradleProperty("kilo.dev.log.chat.content").orNull ?: "off"
    val preview = providers.gradleProperty("kilo.dev.log.chat.preview.max").orNull ?: "160"
    systemProperty("kilo.dev.log.level", level)
    systemProperty("kilo.dev.log.chat.content", content)
    systemProperty("kilo.dev.log.chat.preview.max", preview)
    systemProperty("kilo.dev.storage.isolated", isolated.get().toString())
    systemProperty("kilo.dev.worktree.root", worktreeRoot.get())
}

tasks.named<Delete>("clean") {
    delete(layout.buildDirectory)
}
