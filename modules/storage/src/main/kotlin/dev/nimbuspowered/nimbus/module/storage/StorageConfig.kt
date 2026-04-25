package dev.nimbuspowered.nimbus.module.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StorageConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val prefix: String = "templates",
    @SerialName("access_key") val accessKey: String = "",
    @SerialName("secret_key") val secretKey: String = "",
    @SerialName("path_style") val pathStyle: Boolean = false,
    @SerialName("auto_sync_on_start") val autoSyncOnStart: Boolean = false
)
