import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    jacoco
}

allprojects {
    group = "dev.nimbuspowered.nimbus"
    version = findProperty("nimbusVersion") as String? ?: "0.0.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    plugins.withId("java") {
        apply(plugin = "jacoco")

        extensions.configure<JacocoPluginExtension> {
            toolVersion = "0.8.12"
        }

        tasks.withType<Test>().configureEach {
            finalizedBy(tasks.withType<JacocoReport>())
        }

        tasks.withType<JacocoReport>().configureEach {
            dependsOn(tasks.withType<Test>())
            // Only count our own code — exclude shaded/relocated third-party deps
            // (FancyNpcs, Guava, zstd, Apache Commons, etc.) that end up on
            // compileClasspath via shadow-jar configurations.
            classDirectories.setFrom(
                files(classDirectories.files.map { f ->
                    fileTree(f) {
                        include("dev/nimbuspowered/**")
                    }
                })
            )
            reports {
                xml.required.set(true)
                html.required.set(true)
                csv.required.set(false)
            }
        }
    }
}

// Aggregated coverage across every subproject that produced an exec file.
val jacocoAggregatedReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Aggregate JaCoCo coverage across all subprojects."

    dependsOn(subprojects.flatMap { it.tasks.withType<Test>() })

    executionData.setFrom(
        subprojects.flatMap { sp ->
            sp.fileTree(sp.layout.buildDirectory.dir("jacoco")) {
                include("*.exec")
            }
        }
    )

    sourceDirectories.setFrom(
        subprojects.mapNotNull { sp ->
            sp.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
                ?.sourceSets
                ?.findByName("main")
                ?.allSource
                ?.srcDirs
        }.flatten()
    )

    classDirectories.setFrom(
        files(
            subprojects.flatMap { sp ->
                val main = sp.extensions.findByType(org.gradle.api.plugins.JavaPluginExtension::class.java)
                    ?.sourceSets?.findByName("main")
                    ?: return@flatMap emptyList<org.gradle.api.file.FileTree>()
                main.output.classesDirs.files.map { classDir ->
                    sp.fileTree(classDir) { include("dev/nimbuspowered/**") }
                }
            }
        )
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregated/html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/aggregated/report.xml"))
    }
}
