plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":shared"))

    val ktor = "3.6.1"
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-config-yaml:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-server-cors:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")

    implementation("org.jetbrains.exposed:exposed-core:1.6.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.6.1")
    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
