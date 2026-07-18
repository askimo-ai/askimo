/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    components {
        withModule("dev.detekt:detekt-api") {
            allVariants {
                withCapabilities {
                    addCapability("dev.detekt", "detekt-api-test-fixtures", id.version)
                }
            }
        }
    }
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain((property("jvmVersion") as String).toInt())
}

tasks.test {
    useJUnitPlatform()
}
