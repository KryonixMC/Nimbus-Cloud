plugins {
    kotlin("jvm")
}

// No dependency on nimbus-core! This is intentional.
// External module developers depend only on this artifact.
dependencies {
    // Ktor routing for Route type in registerRoutes()
    compileOnly("io.ktor:ktor-server-core:3.1.1")
    // Coroutines for suspend functions and CoroutineScope
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // Exposed for DatabaseManager
    compileOnly("org.jetbrains.exposed:exposed-core:0.57.0")
    compileOnly("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    // SLF4J for logging
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("io.ktor:ktor-server-core:3.1.1")
    testImplementation("org.jetbrains.exposed:exposed-core:0.57.0")
    testImplementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
