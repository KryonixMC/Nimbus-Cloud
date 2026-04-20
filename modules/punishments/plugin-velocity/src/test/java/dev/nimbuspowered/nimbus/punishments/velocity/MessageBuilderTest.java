package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageBuilderTest {

    private String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void prefersPreRenderedKickMessage() {
        JsonObject rec = new JsonObject();
        rec.addProperty("kickMessage", "&cYou are banned: &fcheating");
        rec.addProperty("type", "BAN");
        String text = plain(MessageBuilder.kickMessage(rec));
        assertTrue(text.contains("You are banned"));
        assertTrue(text.contains("cheating"));
    }

    @Test
    void fallsBackToBuiltTemplateWhenNoKickMessage() {
        JsonObject rec = new JsonObject();
        rec.addProperty("type", "KICK");
        rec.addProperty("reason", "spam");
        rec.addProperty("issuerName", "OpUser");
        rec.addProperty("scope", "NETWORK");
        String text = plain(MessageBuilder.kickMessage(rec));
        assertTrue(text.contains("kicked from the network"));
        assertTrue(text.contains("spam"));
        assertTrue(text.contains("OpUser"));
    }

    @Test
    void tempbanIncludesExpiryWhenRemainingPositive() {
        JsonObject rec = new JsonObject();
        rec.addProperty("type", "TEMPBAN");
        rec.addProperty("reason", "x");
        rec.addProperty("issuerName", "mod");
        rec.addProperty("scope", "NETWORK");
        rec.addProperty("remainingSeconds", 3600L);
        String text = plain(MessageBuilder.kickMessage(rec));
        assertTrue(text.contains("Expires in"));
        assertTrue(text.contains("1h"), "expected 1h in: " + text);
    }

    @Test
    void scopedBanUsesScopeTargetInHeader() {
        JsonObject rec = new JsonObject();
        rec.addProperty("type", "BAN");
        rec.addProperty("reason", "x");
        rec.addProperty("scope", "GROUP");
        rec.addProperty("scopeTarget", "Survival");
        String text = plain(MessageBuilder.kickMessage(rec));
        assertTrue(text.contains("Survival"));
    }

    @Test
    void muteLineUsesRenderedKickMessageWhenProvided() {
        JsonObject rec = new JsonObject();
        rec.addProperty("kickMessage", "§cYou are muted: §fspam");
        assertEquals("§cYou are muted: §fspam", MessageBuilder.formatMuteLine(rec));
    }

    @Test
    void muteLineDefaultsForPermanent() {
        JsonObject rec = new JsonObject();
        rec.addProperty("reason", "caps");
        String line = MessageBuilder.formatMuteLine(rec);
        assertTrue(line.contains("muted"));
        assertTrue(line.contains("caps"));
    }

    @Test
    void muteLineIncludesDurationForTempmute() {
        JsonObject rec = new JsonObject();
        rec.addProperty("reason", "caps");
        rec.addProperty("remainingSeconds", 90000L); // 1d 1h
        String line = MessageBuilder.formatMuteLine(rec);
        assertTrue(line.contains("1d"), line);
        assertTrue(line.contains("caps"));
    }
}
