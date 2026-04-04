plugins {
    kotlin("jvm")
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
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

kotlin {
    jvmToolchain(21)
}
