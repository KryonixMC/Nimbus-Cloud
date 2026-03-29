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

// Embed the Signs plugin JAR as a resource (extracted at runtime to plugins/)
val signsJar = tasks.register("copySignsJar", Copy::class) {
    dependsOn(project(":nimbus-signs").tasks.named("shadowJar"))
    from(project(":nimbus-signs").tasks.named("shadowJar").map { (it as Jar).archiveFile })
    into(layout.buildDirectory.dir("resources/main/plugins"))
    rename { "nimbus-signs.jar" }
}

tasks.processResources {
    dependsOn(pluginJar, sdkJar, signsJar)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    dependsOn(pluginJar, sdkJar, signsJar)
}

kotlin {
    jvmToolchain(21)
}
