plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow") version "9.0.0-beta12"
    application
}

application {
    mainClass.set("dev.nimbus.NimbusKt")
}

dependencies {
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Kotlin Serialization (JSON for Velocity config)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // TOML parsing
    implementation("com.akuleshov7:ktoml-core:0.5.2")
    implementation("com.akuleshov7:ktoml-file:0.5.2")

    // Interactive console
    implementation("org.jline:jline:3.28.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // HTTP client for downloading server JARs
    implementation("io.ktor:ktor-client-cio:3.1.1")

    // REST API (Ktor Server)
    implementation("io.ktor:ktor-server-core:3.1.1")
    implementation("io.ktor:ktor-server-cio:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-auth:3.1.1")
    implementation("io.ktor:ktor-server-websockets:3.1.1")
    implementation("io.ktor:ktor-server-cors:3.1.1")
    implementation("io.ktor:ktor-server-status-pages:3.1.1")
}

// Embed the Velocity hub plugin JAR as a resource so Nimbus can auto-deploy it
val pluginJar = tasks.register("copyPluginJar", Copy::class) {
    dependsOn(project(":nimbus-velocity-plugin").tasks.named("jar"))
    from(project(":nimbus-velocity-plugin").tasks.named("jar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-hub.jar" }
}

tasks.processResources {
    dependsOn(pluginJar)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    dependsOn(pluginJar)
}

kotlin {
    jvmToolchain(21)
}
