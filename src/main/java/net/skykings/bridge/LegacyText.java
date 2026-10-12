package net.skykings.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Hypixel item names and lore use old "§" colour codes (e.g. "§6Hyperion §d✪").
 * This turns them into styled text for tooltips. Only colours and bold/italic/
 * underline/strikethrough are kept: no click or hover actions.
 */
final class LegacyText {
    private LegacyText() {}

    private static final String CODES = "0123456789abcdef";
    private static final ChatFormatting[] COLORS = {
            ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
            ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.GRAY,
            ChatFormatting.DARK_GRAY, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
            ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW, ChatFormatting.WHITE};

    static Component parse(String text, ChatFormatting base) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY.withColor(base).withItalic(false);
        StringBuilder run = new StringBuilder();
        boolean scrambled = false; // §k text: Hypixel puts filler letters there (e.g. "§ka§r"), so it's left out
        String s = text == null ? "" : text.replaceAll("\\p{Cntrl}", "").replaceAll("\\p{Cf}", "");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(++i));
                if (!run.isEmpty()) {
                    out.append(Component.literal(run.toString()).setStyle(style));
                    run.setLength(0);
                }
                int color = CODES.indexOf(code);
                if (color >= 0 || code == 'r') scrambled = false;
                if (code == 'k') scrambled = true;
                if (color >= 0) style = Style.EMPTY.withColor(COLORS[color]).withItalic(false);
                else if (code == 'l') style = style.withBold(true);
                else if (code == 'o') style = style.withItalic(true);
                else if (code == 'n') style = style.withUnderlined(true);
                else if (code == 'm') style = style.withStrikethrough(true);
                else if (code == 'r') style = Style.EMPTY.withColor(base).withItalic(false);
                continue; // unknown codes are dropped
            }
            if (!scrambled) run.append(c);
        }
        if (!run.isEmpty()) out.append(Component.literal(run.toString()).setStyle(style));
        return out;
    }

    /** Without any § codes. */
    static String strip(String text) {
        return text == null ? "" : text.replaceAll("§.?", "");
    }

    // The game's RGB for each code (ChatFormatting.getColor isn't on every supported version).
    private static final int[] RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** The colour of the first § colour code (e.g. an item name's rarity), as RGB, or the fallback. */
    static int firstColor(String text, int fallback) {
        if (text == null) return fallback;
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '§') {
                int color = CODES.indexOf(Character.toLowerCase(text.charAt(i + 1)));
                if (color >= 0) return RGB[color];
            }
        }
        return fallback;
    }
}
