package dev.kryonix.nimbus.service

enum class ServiceState {
    PREPARING,  // Template being copied
    STARTING,   // JVM process started, waiting for "Done"
    READY,      // Server is accepting players
    STOPPING,   // Graceful shutdown in progress
    STOPPED,    // Clean shutdown complete
    CRASHED     // Process exited unexpectedly
}
