package dev.nimbuspowered.nimbus.service

enum class ServiceState {
    PREPARING,  // Template being copied
    PREPARED,   // Template ready, waiting in warm pool (not started yet)
    STARTING,   // JVM process started, waiting for "Done"
    READY,      // Server is accepting players
    DRAINING,   // No new players, waiting for existing to leave before stop
    STOPPING,   // Graceful shutdown in progress
    STOPPED,    // Clean shutdown complete
    CRASHED     // Process exited unexpectedly
}
