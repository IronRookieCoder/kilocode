import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.backend")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    implementation(project(":backend"))
    implementation(project(":shared"))
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // 协程由平台测试框架提供（打补丁的IntelliJ构建）：引入Maven coroutines会遮蔽平台
    // 版本，破坏IntellijCoroutines facade（runBlockingWithParallelismCompensation崩坏，
    // 见B1报告）。Fixture所需的CoroutineScope等API运行期从平台jar解析。
    testImplementation(testFixtures(project(":shared")))
    // `@TestApplication` (test-framework-junit5) requires JUnit Jupiter >= 5.13,
    // while kotlin-test-junit5 pins the test classpath to 5.10.1.
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
}

tasks.test {
    useJUnitPlatform()
}
