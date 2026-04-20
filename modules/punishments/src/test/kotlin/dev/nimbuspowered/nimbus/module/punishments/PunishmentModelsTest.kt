package dev.nimbuspowered.nimbus.module.punishments

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PunishmentModelsTest {

    @Test
    fun `scope parse accepts case-insensitive and falls back to NETWORK`() {
        assertEquals(PunishmentScope.GROUP, PunishmentScope.parse("group"))
        assertEquals(PunishmentScope.SERVICE, PunishmentScope.parse("SERVICE"))
        assertEquals(PunishmentScope.NETWORK, PunishmentScope.parse(null))
        assertEquals(PunishmentScope.NETWORK, PunishmentScope.parse("bogus"))
    }

    @Test
    fun `appliesIn NETWORK always matches`() {
        val rec = record(PunishmentScope.NETWORK, null)
        assertTrue(rec.appliesIn(null, null))
        assertTrue(rec.appliesIn("Lobby", "Lobby-1"))
    }

    @Test
    fun `appliesIn GROUP requires matching group`() {
        val rec = record(PunishmentScope.GROUP, "BedWars")
        assertTrue(rec.appliesIn("BedWars", "BedWars-3"))
        assertFalse(rec.appliesIn("Lobby", "Lobby-1"))
        assertFalse(rec.appliesIn(null, null))
    }

    @Test
    fun `appliesIn SERVICE requires matching service`() {
        val rec = record(PunishmentScope.SERVICE, "Lobby-1")
        assertTrue(rec.appliesIn("Lobby", "Lobby-1"))
        assertFalse(rec.appliesIn("Lobby", "Lobby-2"))
    }

    @Test
    fun `type helpers correctly classify`() {
        assertTrue(PunishmentType.BAN.blocksLogin())
        assertTrue(PunishmentType.IPBAN.blocksLogin())
        assertFalse(PunishmentType.MUTE.blocksLogin())
        assertTrue(PunishmentType.MUTE.blocksChat())
        assertTrue(PunishmentType.TEMPBAN.isTemporary())
        assertFalse(PunishmentType.BAN.isTemporary())
        assertFalse(PunishmentType.KICK.isRevocable())
        assertFalse(PunishmentType.WARN.isRevocable())
        assertTrue(PunishmentType.BAN.isRevocable())
    }

    private fun record(scope: PunishmentScope, target: String?) = PunishmentRecord(
        id = 1, type = PunishmentType.BAN,
        targetUuid = "u", targetName = "n", targetIp = null,
        reason = "", issuer = "c", issuerName = "C",
        issuedAt = "2020-01-01T00:00:00Z", expiresAt = null,
        active = true, revokedBy = null, revokedAt = null, revokeReason = null,
        scope = scope, scopeTarget = target
    )
}
