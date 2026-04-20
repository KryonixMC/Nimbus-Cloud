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

// Override the annotation-generated velocity-plugin.json with the correct version from gradle.properties
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
}

// Make the default jar task produce the shadow jar
tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
