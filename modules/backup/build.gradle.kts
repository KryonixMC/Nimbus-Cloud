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

    // Native archiver — shaded into the core fat JAR; here compileOnly only
    compileOnly("com.github.luben:zstd-jni:1.5.6-4")
    compileOnly("org.apache.commons:commons-compress:1.27.1")
}

kotlin {
    jvmToolchain(21)
}
