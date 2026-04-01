plugins {
    java
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/repository/public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // Gson: provided at runtime by Paper/Velocity, only needed at compile time
    compileOnly("com.google.code.gson:gson:2.11.0")

    // Paper API: compileOnly for the plugin main class (provided by Paper at runtime)
    // Also provides Adventure API classes for the compat layer
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // ProtocolLib: compileOnly for fake player packets (provided at runtime as a plugin)
    compileOnly("com.comphenix.protocol:ProtocolLib:5.4.0-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
