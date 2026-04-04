plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    compileOnly(project(":nimbus-core"))
    compileOnly("io.ktor:ktor-server-core:3.1.1")
    compileOnly("io.ktor:ktor-server-content-negotiation:3.1.1")
    compileOnly("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
}

kotlin {
    jvmToolchain(21)
}
