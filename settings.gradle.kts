rootProject.name = "askimo"

include("cli")
include("shared")
include("shared-jvm")
include("desktop")
include("desktop-shared")
include("detekt-rules")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
        google()
    }
}
