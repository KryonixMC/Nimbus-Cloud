import java.net.URI

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
    // Protocol module (shared cluster messages)
    implementation(project(":nimbus-protocol"))

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

    // Database (Exposed + drivers)
    implementation("org.jetbrains.exposed:exposed-core:0.57.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("com.mysql:mysql-connector-j:9.2.0")
    implementation("org.postgresql:postgresql:42.7.5")

    // REST API (Ktor Server)
    implementation("io.ktor:ktor-server-core:3.1.1")
    implementation("io.ktor:ktor-server-cio:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-auth:3.1.1")
    implementation("io.ktor:ktor-server-websockets:3.1.1")
    implementation("io.ktor:ktor-server-cors:3.1.1")
    implementation("io.ktor:ktor-server-status-pages:3.1.1")
    implementation("io.ktor:ktor-server-rate-limit:3.1.1")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.ktor:ktor-server-test-host:3.1.1")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.1.1")
    testImplementation("io.ktor:ktor-client-websockets:3.1.1")
}

tasks.test {
    useJUnitPlatform()
}

// Embed the Velocity cloud plugin JAR (shadow, includes SDK) as a resource
val pluginJar = tasks.register("copyPluginJar", Copy::class) {
    dependsOn(project(":nimbus-bridge").tasks.named("shadowJar"))
    from(project(":nimbus-bridge").tasks.named("shadowJar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-bridge.jar" }
}

// Embed the SDK JAR as a resource so Nimbus can auto-deploy it to backend servers
val sdkJar = tasks.register("copySdkJar", Copy::class) {
    dependsOn(project(":nimbus-sdk").tasks.named("jar"))
    from(project(":nimbus-sdk").tasks.named("jar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-sdk.jar" }
}

// Download ProtocolLib and embed as resource for auto-deploy to backend servers
val protocolLibVersion = "5.4.0" // GitHub release, SNAPSHOT in Maven repo
val downloadProtocolLib = tasks.register("downloadProtocolLib") {
    val outputFile = layout.buildDirectory.file("resources/main/plugins/ProtocolLib.jar")
    outputs.file(outputFile)
    doLast {
        val url = "https://github.com/dmulloy2/ProtocolLib/releases/download/$protocolLibVersion/ProtocolLib.jar"
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        if (!target.exists()) {
            logger.lifecycle("Downloading ProtocolLib $protocolLibVersion...")
            URI.create(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            logger.lifecycle("Downloaded ProtocolLib to ${target.absolutePath}")
        }
    }
}

// Download FancyNpcs and embed as resource for auto-deploy to backend servers
val fancyNpcsVersion = "2.9.2.349"
val downloadFancyNpcs = tasks.register("downloadFancyNpcs") {
    val outputFile = layout.buildDirectory.file("resources/main/plugins/FancyNpcs.jar")
    outputs.file(outputFile)
    doLast {
        val url = "https://hangar.papermc.io/api/v1/projects/Oliver/FancyNpcs/versions/$fancyNpcsVersion/PAPER/download"
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        if (!target.exists()) {
            logger.lifecycle("Downloading FancyNpcs $fancyNpcsVersion...")
            URI.create(url).toURL().openStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            logger.lifecycle("Downloaded FancyNpcs to ${target.absolutePath}")
        }
    }
}

// Embed the Display plugin JAR as a resource (extracted at runtime to plugins/)
val displayJar = tasks.register("copyDisplayJar", Copy::class) {
    dependsOn(project(":nimbus-display").tasks.named("shadowJar"))
    from(project(":nimbus-display").tasks.named("shadowJar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-display.jar" }
}

// Embed the Perms plugin JAR as a resource (extracted at runtime to plugins/)
val permsJar = tasks.register("copyPermsJar", Copy::class) {
    dependsOn(project(":nimbus-perms").tasks.named("shadowJar"))
    from(project(":nimbus-perms").tasks.named("shadowJar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-perms.jar" }
}

tasks.processResources {
    dependsOn(pluginJar, sdkJar, displayJar, permsJar, downloadProtocolLib, downloadFancyNpcs)
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    dependsOn(pluginJar, sdkJar, displayJar, permsJar, downloadProtocolLib, downloadFancyNpcs)
}

kotlin {
    jvmToolchain(21)
}
