package dev.nimbuspowered.nimbus.punishments.velocity;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Builds the Adventure {@link Component} a player sees when denied at the proxy. */
public final class MessageBuilder {

    private MessageBuilder() {}

    /**
     * Prefer the pre-rendered {@code kickMessage} the controller put in the response
     * (or event data) — it respects the operator's templates from
     * {@code config/modules/punishments/messages.toml}. Fall back to an in-code
     * builder if the field is missing (legacy controllers without template support).
     */
    static Component kickMessage(JsonObject record) {
        String rendered = optString(record, "kickMessage", "");
        if (!rendered.isBlank()) {
            return LegacyComponentSerializer.legacySection().deserialize(rendered);
        }

        String type = record.has("type") ? record.get("type").getAsString() : "BAN";
        String reason = optString(record, "reason", "No reason given");
        String issuer = optString(record, "issuerName", "Console");
        String scope = optString(record, "scope", "NETWORK");
        String scopeTarget = optString(record, "scopeTarget", "");
        Long remaining = record.has("remainingSeconds") && !record.get("remainingSeconds").isJsonNull()
                ? record.get("remainingSeconds").getAsLong() : null;

        Component header;
        switch (type) {
            case "KICK":
                header = Component.text("You were kicked from the network", NamedTextColor.RED);
                break;
            case "IPBAN":
                header = Component.text("Your IP is banned from the network", NamedTextColor.RED);
                break;
            case "TEMPBAN":
                header = Component.text("You are temporarily banned", NamedTextColor.RED);
                break;
            default:
                header = scope.equals("NETWORK")
                    ? Component.text("You are banned from the network", NamedTextColor.RED)
                    : Component.text("You are banned from " + (scopeTarget.isBlank() ? scope : scopeTarget), NamedTextColor.RED);
                break;
        }

        Component msg = header
            .append(Component.newline()).append(Component.newline())
            .append(Component.text("Reason: ", NamedTextColor.GRAY))
            .append(Component.text(reason, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("By: ", NamedTextColor.GRAY))
            .append(Component.text(issuer, NamedTextColor.WHITE));

        if (remaining != null && remaining > 0) {
            msg = msg.append(Component.newline())
                .append(Component.text("Expires in: ", NamedTextColor.GRAY))
                .append(Component.text(formatDuration(remaining), NamedTextColor.WHITE));
        }
        return msg;
    }

    /**
     * Mute message for chat feedback. Uses the pre-rendered {@code kickMessage}
     * field when available (same pipeline as bans), else builds a default line.
     */
    static String formatMuteLine(JsonObject record) {
        String rendered = optString(record, "kickMessage", "");
        if (!rendered.isBlank()) return rendered;

        String reason = optString(record, "reason", "No reason");
        Long remaining = record.has("remainingSeconds") && !record.get("remainingSeconds").isJsonNull()
                ? record.get("remainingSeconds").getAsLong() : null;
        if (remaining == null || remaining <= 0) return "§cYou are muted: §f" + reason;
        return "§cYou are muted for §f" + formatDuration(remaining) + "§c: §f" + reason;
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : fallback;
    }

    private static String formatDuration(long seconds) {
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0 || sb.length() == 0) sb.append(Math.max(m, 1)).append("m");
        return sb.toString().trim();
    }
}
