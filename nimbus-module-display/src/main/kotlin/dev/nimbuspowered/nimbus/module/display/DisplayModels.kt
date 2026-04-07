package dev.nimbuspowered.nimbus.module.display

import kotlinx.serialization.Serializable

// ── Display DTOs ───────────────────────────────────────────────────

@Serializable
data class DisplayResponse(
    val name: String,
    val sign: SignDisplayResponse,
    val npc: NpcDisplayResponse,
    val states: Map<String, String>
)

@Serializable
data class SignDisplayResponse(
    val line1: String,
    val line2: String,
    val line3: String,
    val line4Online: String,
    val line4Offline: String
)

@Serializable
data class NpcDisplayResponse(
    val displayName: String,
    val subtitle: String,
    val subtitleOffline: String,
    val floatingItem: String,
    val statusItems: Map<String, String>,
    val inventory: NpcInventoryResponse
)

@Serializable
data class NpcInventoryResponse(
    val title: String,
    val size: Int,
    val itemName: String,
    val itemLore: List<String>
)

@Serializable
data class DisplayListResponse(
    val displays: List<DisplayResponse>,
    val total: Int
)

@Serializable
data class UpdateDisplayRequest(
    val sign: SignDisplayResponse? = null,
    val npc: NpcDisplayResponse? = null,
    val states: Map<String, String>? = null
)
