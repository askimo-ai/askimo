plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

// Kotlin Multiplatform auto-names the jvm target's jar "<project.name>-jvm" (i.e. "shared-jvm"),
// which collides with the separate, actual ":shared-jvm" module's own jar of the same name —
// breaking the cli distribution task (duplicate archive entry) and the Maven publication
// artifactId. Rename this project's archives to disambiguate.
base {
    archivesName.set("shared-common")
}

kotlin {
    jvmToolchain((property("jvmVersion") as String).toInt())

    jvm()
    // Future targets, enable when mobile app work starts:
    // androidTarget()
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.datetime)
            api(libs.koin.core)
            api(libs.bundles.logging)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            // Multiplatform plugin auto-creates per-target publications (e.g. "jvm", "kotlinMultiplatform")
            // with artifactId = "<project.name>-<targetName>" by default (e.g. "shared-jvm"), which collides
            // with the separate ":shared-jvm" module's published artifactId. Disambiguate explicitly.
            artifactId = artifactId.replaceFirst("shared", "shared-common")
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/askimo-ai/askimo")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
