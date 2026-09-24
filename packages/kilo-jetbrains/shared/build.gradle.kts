plugins {
    alias(libs.plugins.rpc)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)
    }

    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)

    // 共享Fixture（stability-fixture.kt）只依赖协程与JSON：compileOnly避免把Maven版
    // coroutines传递给消费模块（遮蔽平台自带协程会破坏IntellijCoroutines facade）；
    // 共享自身测试的运行时协程由上方testImplementation(coroutines-test)提供。
    testFixturesCompileOnly(libs.kotlinx.coroutines.core.jvm)
    testFixturesCompileOnly(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnitPlatform()
}
