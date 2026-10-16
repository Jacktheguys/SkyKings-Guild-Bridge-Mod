package net.skykings.bridge;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Name colours picked by guild members (sent by the bridge). Used to colour
 * their names in guild chat and /bc lines: one of these colours, or any
 * "#rrggbb" for SkyKings patrons (the server checks who's a patron).
 */
final class NameColors {
    private NameColors() {}

    /** The choices, in the order the settings screen shows them ("none" first). */
    static final List<String> CHOICES = List.of(
            "none", "white", "gray", "red", "dark_red", "gold", "yellow", "green",
            "dark_green", "aqua", "dark_aqua", "blue", "dark_blue", "light_purple", "dark_purple");

    // Minecraft's chat colours (the same values the game uses for these names).
    private static final Map<String, Integer> RGB = Map.ofEntries(
            Map.entry("white", 0xFFFFFF), Map.entry("gray", 0xAAAAAA), Map.entry("red", 0xFF5555),
            Map.entry("dark_red", 0xAA0000), Map.entry("gold", 0xFFAA00), Map.entry("yellow", 0xFFFF55),
            Map.entry("green", 0x55FF55), Map.entry("dark_green", 0x00AA00), Map.entry("aqua", 0x55FFFF),
            Map.entry("dark_aqua", 0x00AAAA), Map.entry("blue", 0x5555FF), Map.entry("dark_blue", 0x0000AA),
            Map.entry("light_purple", 0xFF55FF), Map.entry("dark_purple", 0xAA00AA));

    private static final Map<String, Integer> byName = new ConcurrentHashMap<>(); // lowercase name -> rgb

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** "#rrggbb": a custom colour (SkyKings patrons). */
    static boolean isHex(String color) {
        return color != null && HEX.matcher(color).matches();
    }

    /** The colour's RGB, or null for "none" / anything unknown. */
    static Integer rgb(String color) {
        if (color == null) return null;
        if (isHex(color)) return Integer.parseInt(color.substring(1), 16);
        return RGB.get(color);
    }

    /** A message text colour setting: custom (hex) colours only count for patrons. */
    static Integer textRgb(String color, String fallback) {
        if (isHex(color) && !GuildBridgeMod.connection().isPatron()) return RGB.get(fallback);
        Integer rgb = rgb(color);
        return rgb != null ? rgb : RGB.get(fallback);
    }

    static Integer rgbOf(String player) {
        return byName.get(player.toLowerCase());
    }

    /** From the bridge: { name: colour or null }; replace = this is the full list. */
    static void update(Map<String, String> colors, boolean replace) {
        if (replace) byName.clear();
        colors.forEach((name, color) -> {
            Integer rgb = rgb(color);
            if (rgb == null) byName.remove(name.toLowerCase());
            else byName.put(name.toLowerCase(), rgb);
        });
    }

    /**
     * A guild chat line with the sender's name recoloured (if they picked a
     * colour). Everything else in the line keeps Hypixel's own styling.
     */
    static Component recolor(Component message) {
        if (byName.isEmpty() || !BridgeConfig.get().customColors) return message;
        GuildChatParser.ChatLine chat = GuildChatParser.parseChat(GuildChatParser.strip(message.getString()));
        if (chat == null) return message;
        Integer color = byName.get(chat.sender().toLowerCase());
        if (color == null) return message;

        MutableComponent out = Component.empty();
        boolean[] done = {false};
        message.visit((style, text) -> {
            int at = done[0] ? -1 : text.indexOf(chat.sender());
            if (at < 0) {
                out.append(Component.literal(text).setStyle(style));
            } else {
                done[0] = true;
                out.append(Component.literal(text.substring(0, at)).setStyle(style));
                out.append(Component.literal(chat.sender()).setStyle(style.withColor(color)));
                out.append(Component.literal(text.substring(at + chat.sender().length())).setStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return done[0] ? out : message;
    }
}
