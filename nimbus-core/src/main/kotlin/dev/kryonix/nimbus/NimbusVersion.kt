package dev.kryonix.nimbus

object NimbusVersion {
    val version: String by lazy {
        NimbusVersion::class.java.`package`?.implementationVersion ?: "dev"
    }
}
