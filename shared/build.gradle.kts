plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
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
    api(libs.bundles.langchain4j)

    api(libs.kotlinx.serialization.json.jvm)
    api(libs.kotlinx.coroutines.core)
    api(kotlin("stdlib"))

    api(libs.bundles.lucene)

    api(libs.bundles.jackson)

    api(libs.sqlite.jdbc)
    api(libs.hikaricp)
    api(libs.bundles.exposed)

    api(libs.bundles.koin)

    implementation(libs.caffeine)

    api(libs.bundles.logging)

    implementation(libs.bundles.tika) {
        exclude(group = "org.eclipse.angus", module = "angus-activation")
    }

    implementation(libs.jsoup)

    implementation(libs.bundles.commonmark)

    // Testing
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.bundles.koin.test)

    // Test fixtures - shared test utilities
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()

    // Enable Vector API for better JVector performance
    jvmArgs("--add-modules", "jdk.incubator.vector")
}
kotlin {
    jvmToolchain((property("jvmVersion") as String).toInt())
    compilerOptions {
        // Preserve parameter names in bytecode so LangChain4j @Tool/@P reflection works correctly.
        // Without this, tool method parameters appear as arg0, arg1 in the LLM's tool schema.
        javaParameters = true
    }
}
