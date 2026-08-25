plugins {
    alias(libs.plugins.kotlin)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        intellijIdea(libs.versions.intellij.platform)
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.backend")
        bundledPlugin("com.intellij.mcpServer")
    }

    implementation(project(":cs-cloud"))
}

tasks.test {
    useJUnitPlatform()
}
