plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        }
    }
    js { browser() }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            api("io.ktor:ktor-client-core:3.6.1")
            implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
        }
        jsMain.dependencies {
            implementation("io.ktor:ktor-client-js:3.6.1")
        }
    }
}
