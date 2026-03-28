plugins {
    java
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Gson: provided at runtime by Paper/Velocity, only needed at compile time
    compileOnly("com.google.code.gson:gson:2.11.0")

    // Paper API: compileOnly for the plugin main class (provided by Paper at runtime)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
