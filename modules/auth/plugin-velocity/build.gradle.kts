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
    // Pull in the SDK so we can reuse NimbusClient + NimbusEventStream if needed.
    // It's shaded into the final JAR via shadowJar; we exclude Bukkit-only classes below.
    implementation(project(":nimbus-sdk"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
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
    doLast {
        val jsonFile = destinationDirectory.file("velocity-plugin.json").get().asFile
        if (jsonFile.exists()) {
            val nimbusVersion = project.rootProject.property("nimbusVersion").toString()
            val content = jsonFile.readText().replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version":"$nimbusVersion"""")
            jsonFile.writeText(content)
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Gson is provided by Velocity runtime — exclude from shadow to avoid zip entry issues
    exclude("com/google/gson/**")
    // Bukkit-coupled SDK classes aren't usable on Velocity and would fail to class-load
    exclude("dev/nimbuspowered/nimbus/sdk/NimbusSdkPlugin*")
    exclude("dev/nimbuspowered/nimbus/sdk/compat/**")
    exclude("dev/nimbuspowered/nimbus/sdk/NimbusSelfService*")
    exclude("dev/nimbuspowered/nimbus/sdk/PlayerTracker*")
    exclude("dev/nimbuspowered/nimbus/sdk/TpsTracker*")
    exclude("plugin.yml")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
