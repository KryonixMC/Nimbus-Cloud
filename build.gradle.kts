plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
}

allprojects {
    group = "dev.nimbus"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
