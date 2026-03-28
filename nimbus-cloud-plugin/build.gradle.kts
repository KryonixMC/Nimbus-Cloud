plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    implementation(project(":nimbus-sdk"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Gson is provided by Velocity runtime — exclude from shadow to avoid zip entry issues
    exclude("com/google/gson/**")
}

// Make the default jar task produce the shadow jar
tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
