package dev.nimbuspowered.nimbus.module.punishments

/**
 * How far a punishment reaches.
 *
 * - [NETWORK] — blocks login / mutes / kicks on every server (default).
 * - [GROUP]   — takes effect only on services that belong to the target group.
 *              `scopeTarget` holds the group name.
 * - [SERVICE] — takes effect only on the named service instance.
 *              `scopeTarget` holds the service name (`Lobby-1`, `BedWars-3`, …).
 *
 * Scoped BAN / TEMPBAN / IPBAN block `ServerPreConnectEvent` for matching targets
 * (the player can still join the network, just not that server/group). Scoped
 * MUTE / TEMPMUTE block chat only while the player is on a matching service.
 */
enum class PunishmentScope {
    NETWORK,
    GROUP,
    SERVICE;

    companion object {
        fun parse(raw: String?): PunishmentScope =
            raw?.uppercase()?.let { runCatching { valueOf(it) }.getOrNull() } ?: NETWORK
    }
}
