package dev.nimbuspowered.nimbus.template

import kotlinx.serialization.Serializable

// PaperMC API models
@Serializable
internal data class PaperProjectResponse(val versions: List<String>)

@Serializable
internal data class PaperBuildsResponse(val builds: List<PaperBuildEntry>)

@Serializable
internal data class PaperBuildEntry(val build: Int, val downloads: Map<String, PaperDownloadEntry>)

@Serializable
internal data class PaperDownloadEntry(val name: String, val sha256: String)

// Purpur API models
@Serializable
internal data class PurpurProjectResponse(val versions: List<String>)

@Serializable
internal data class PurpurVersionResponse(val builds: PurpurBuilds)

@Serializable
internal data class PurpurBuilds(val latest: String, val all: List<String>)

// Forge API models
@Serializable
internal data class ForgePromotions(val promos: Map<String, String> = emptyMap())

// Fabric API models
@Serializable
internal data class FabricLoaderVersion(val version: String, val stable: Boolean = true)

@Serializable
internal data class FabricGameVersion(val version: String, val stable: Boolean = true)

@Serializable
internal data class FabricInstallerVersion(val version: String, val stable: Boolean = true)

// NeoForge API models
@Serializable
internal data class NeoForgeVersionsResponse(val versions: List<String> = emptyList())

// Modrinth API models (for proxy forwarding mods)
@Serializable
internal data class ModrinthVersionsResponse(val id: String = "", val version_number: String = "", val files: List<ModrinthFile> = emptyList())

@Serializable
internal data class ModrinthFile(val url: String, val filename: String, val primary: Boolean = false)

// GeyserMC API models (for Geyser + Floodgate)
@Serializable
internal data class GeyserProjectResponse(val versions: List<String> = emptyList())

@Serializable
internal data class GeyserBuildsResponse(val builds: List<GeyserBuild> = emptyList())

@Serializable
internal data class GeyserBuild(val build: Int, val downloads: Map<String, GeyserDownload> = emptyMap())

@Serializable
internal data class GeyserDownload(val name: String, val sha256: String = "")

// Pufferfish CI API models
@Serializable
internal data class PufferfishCIResponse(val jobs: List<PufferfishJob> = emptyList())

@Serializable
internal data class PufferfishJob(val name: String, val url: String = "")

@Serializable
internal data class PufferfishBuildResponse(val url: String = "", val artifacts: List<PufferfishArtifact> = emptyList())

@Serializable
internal data class PufferfishArtifact(val fileName: String, val relativePath: String)

// Hangar API models (for Via plugins)
@Serializable
internal data class HangarVersionsResponse(val result: List<HangarVersion>)

@Serializable
internal data class HangarVersion(val name: String, val downloads: Map<String, HangarDownload>)

@Serializable
internal data class HangarDownload(val downloadUrl: String)
