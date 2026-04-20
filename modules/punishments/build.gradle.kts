plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    compileOnly(project(":nimbus-core"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    compileOnly("io.ktor:ktor-server-core:3.1.1")
    compileOnly("io.ktor:ktor-server-content-negotiation:3.1.1")
    compileOnly("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    compileOnly("org.jetbrains.exposed:exposed-core:0.57.0")
    compileOnly("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    compileOnly("com.akuleshov7:ktoml-core:0.5.2")
    compileOnly("com.akuleshov7:ktoml-file:0.5.2")
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    testImplementation(project(":nimbus-core"))
    testImplementation(project(":nimbus-module-api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.mockk:mockk:1.13.14")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation("io.ktor:ktor-server-core:3.1.1")
    testImplementation("org.jetbrains.exposed:exposed-core:0.57.0")
    testImplementation("org.jetbrains.exposed:exposed-jdbc:0.57.0")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    testImplementation("com.akuleshov7:ktoml-core:0.5.2")
    testImplementation("com.akuleshov7:ktoml-file:0.5.2")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
