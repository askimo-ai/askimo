plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
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

dependencies {
    api(compose.desktop.currentOs)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(project(":shared"))
    implementation(libs.konform)
    implementation(libs.bundles.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.material.kolor)
    // Terminal support
    implementation(libs.bundles.jediterm)
    // PDF export (OpenPDF / LibrePDF)
    implementation(libs.openpdf)
    // Word export
    implementation(libs.poi.ooxml)
    // FileKit — consistent native file/folder picker on all platforms
    implementation(libs.filekit.compose)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
