import java.net.URI

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow") version "9.0.0-beta12"
    application
}

application {
    mainClass.set("dev.nimbuspowered.nimbus.NimbusKt")
}

dependencies {
    // Protocol module (shared cluster messages)
    implementation(project(":nimbus-protocol"))

    // Module API (interfaces for NimbusModule, ModuleContext, ModuleCommand)
    api(project(":nimbus-module-api"))


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

    // Database (Exposed + drivers + connection pool)
    implementation("org.jetbrains.exposed:exposed-core:0.57.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("com.mysql:mysql-connector-j:9.2.0")
    implementation("org.postgresql:postgresql:42.7.5")
    implementation("com.zaxxer:HikariCP:5.1.0")

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
    implementation("io.ktor:ktor-server-auth-jwt:3.1.1")

    // Netty engine for cluster server (native TLS/SSL support via sslConnector)
    implementation("io.ktor:ktor-server-netty:3.1.1")
    // Self-signed certificate generation for auto-TLS
    implementation("io.ktor:ktor-network-tls-certificates:3.1.1")

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
    rename { "nimbus-bridge-${project.version}.jar" }
}

// Embed the SDK JAR as a resource so Nimbus can auto-deploy it to backend servers
val sdkJar = tasks.register("copySdkJar", Copy::class) {
    dependsOn(project(":nimbus-sdk").tasks.named("jar"))
    from(project(":nimbus-sdk").tasks.named("jar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-sdk.jar" }
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

// Embed the Punishments Velocity plugin JAR as a resource (deployed to proxies at runtime)
val punishmentsJar = tasks.register("copyPunishmentsJar", Copy::class) {
    dependsOn(project(":nimbus-punishments-velocity").tasks.named("shadowJar"))
    from(project(":nimbus-punishments-velocity").tasks.named("shadowJar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-punishments.jar" }
}

// Embed the Resource Packs plugin JAR as a resource (extracted at runtime to plugins/)
val resourcePacksJar = tasks.register("copyResourcePacksJar", Copy::class) {
    dependsOn(project(":nimbus-resourcepacks").tasks.named("shadowJar"))
    from(project(":nimbus-resourcepacks").tasks.named("shadowJar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-resourcepacks.jar" }
}

tasks.processResources {
    dependsOn(pluginJar, sdkJar, displayJar, permsJar, punishmentsJar, resourcePacksJar, downloadFancyNpcs)
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// Module JARs to embed in the fat JAR (auto-discovered at runtime via modules.list)
val embeddedModules = mapOf(
    ":nimbus-module-perms" to "nimbus-module-perms.jar",
    ":nimbus-module-display" to "nimbus-module-display.jar",
    ":nimbus-module-scaling" to "nimbus-module-scaling.jar",
    ":nimbus-module-players" to "nimbus-module-players.jar",
    ":nimbus-module-punishments" to "nimbus-module-punishments.jar",
    ":nimbus-module-resourcepacks" to "nimbus-module-resourcepacks.jar"
)

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    dependsOn(pluginJar, sdkJar, displayJar, permsJar, punishmentsJar, resourcePacksJar, downloadFancyNpcs)

    // Embed controller module JARs (extracted by SetupWizard to modules/)
    // These are added only to shadowJar to avoid circular dependency during compileKotlin
    for ((projectPath, jarName) in embeddedModules) {
        from(project(projectPath).tasks.named("jar").map { (it as Jar).archiveFile }) {
            into("controller-modules")
            rename { jarName }
        }
    }

    // Generate modules.list index so ModuleManager can discover embedded modules without hardcoding
    doFirst {
        val indexFile = layout.buildDirectory.file("tmp/controller-modules/modules.list").get().asFile
        indexFile.parentFile.mkdirs()
        indexFile.writeText(embeddedModules.values.joinToString("\n") + "\n")
    }
    from(layout.buildDirectory.dir("tmp")) {
        include("controller-modules/modules.list")
    }
}

kotlin {
    jvmToolchain(21)
}
