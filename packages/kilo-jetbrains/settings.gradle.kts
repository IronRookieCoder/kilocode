rootProject.name = "kilo.jetbrains"

include("shared")
include("frontend")
include("backend")
include("cs-cloud")

pluginManagement {
    includeBuild("build-tasks")
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    }
}
