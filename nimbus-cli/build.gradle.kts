plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow") version "9.0.0-beta12"
    application
}

application {
    mainClass.set("dev.nimbuspowered.nimbus.cli.NimbusCliKt")
}

dependencies {
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // TOML parsing (for cli.toml config)
    implementation("com.akuleshov7:ktoml-core:0.5.2")
    implementation("com.akuleshov7:ktoml-file:0.5.2")

    // JLine3 (interactive terminal)
    implementation("org.jline:jline:3.28.0")

    // Ktor Client (REST + WebSocket)
    implementation("io.ktor:ktor-client-cio:3.1.1")
    implementation("io.ktor:ktor-client-websockets:3.1.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(21)
}
