package dev.kryonix.nimbus.sdk;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts legacy Minecraft color codes ({@code &7}, {@code &c}, {@code &l}, etc.)
 * to MiniMessage format so both styles can be used interchangeably.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code &7Player} → {@code <gray>Player}</li>
 *   <li>{@code &c&lBold Red} → {@code <red><bold>Bold Red}</li>
 *   <li>{@code &#ff5555Custom} → {@code <color:#ff5555>Custom}</li>
 *   <li>Already valid MiniMessage like {@code <red>Player} passes through unchanged</li>
 * </ul>
 */
public final class ColorUtil {

    private static final Map<Character, String> COLOR_MAP = Map.ofEntries(
            Map.entry('0', "<black>"),
            Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"),
            Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"),
            Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"),
            Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"),
            Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"),
            Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"),
            Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /**
     * Translates legacy {@code &} color codes to MiniMessage tags.
     * Supports {@code &0-9}, {@code &a-f}, {@code &k-o}, {@code &r}, and {@code &#RRGGBB} hex codes.
     * MiniMessage tags already present are left untouched.
     */
    public static String translate(String input) {
        if (input == null || input.isEmpty()) return input;

        // First: hex codes &#RRGGBB → <color:#RRGGBB>
        String result = HEX_PATTERN.matcher(input).replaceAll("<color:#$1>");

        // Then: standard codes &7 → <gray> etc.
        Matcher matcher = LEGACY_PATTERN.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String replacement = COLOR_MAP.getOrDefault(code, matcher.group());
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private ColorUtil() {}
}
