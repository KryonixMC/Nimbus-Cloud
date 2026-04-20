plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.luckperms.net/releases/") // LuckPerms API
    maven("https://repo.codemc.io/repository/maven-releases/") // PacketEvents
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(project(":nimbus-sdk"))
    compileOnly("com.google.code.gson:gson:2.11.0")
    // LuckPerms API (only present at runtime if LP installed)
    compileOnly("net.luckperms:api:5.4")
    // PacketEvents API (only present at runtime if PE installed — used for Folia name tags)
    compileOnly("com.github.retrooper.packetevents:spigot:2.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(project(":nimbus-sdk"))
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.addAll(listOf("-source", "16", "-target", "16"))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    dependencies {
        exclude(dependency("com.google.code.gson:gson"))
    }
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
