package net.skykings.bridge;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your personal word filter (/gbridge filter): filtered words show as stars in guild
 * chat and bridge messages. Only whole words match, ignoring case ("ass" doesn't
 * touch "class"), and a filtered phrase can contain spaces.
 */
public final class WordFilter {
    private WordFilter() {}

    private static Pattern pattern;
    private static boolean built;

    /** The word list changed: rebuild the pattern next time. */
    static void changed() {
        built = false;
    }

    private static Pattern pattern() {
        if (!built) {
            built = true;
            List<String> parts = new ArrayList<>();
            for (String w : BridgeConfig.get().filteredWords) {
                if (w == null || w.isBlank()) continue;
                // A phrase matches with any spacing between its words.
                List<String> words = new ArrayList<>();
                for (String part : w.trim().split("\\s+")) words.add(Pattern.quote(part));
                parts.add(String.join("\\s+", words));
            }
            pattern = parts.isEmpty() ? null : Pattern.compile(
                    "(?<![\\p{L}\\p{N}])(?:" + String.join("|", parts) + ")(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        return pattern;
    }

    /** `text` with filtered words replaced by stars (same length, so styles stay in place). */
    public static String apply(String text) {
        Pattern p = pattern();
        if (p == null || text == null || text.isEmpty()) return text;
        Matcher m = p.matcher(text);
        if (!m.find()) return text;
        StringBuilder out = new StringBuilder(text);
        do {
            for (int i = m.start(); i < m.end(); i++) {
                if (!Character.isWhitespace(out.charAt(i))) out.setCharAt(i, '*');
            }
        } while (m.find());
        return out.toString();
    }

    /** The same for a chat line made of styled pieces; a word split across pieces is still caught. */
    public static Component apply(Component message) {
        if (pattern() == null) return message;
        String full = message.getString();
        String masked = apply(full);
        if (masked.equals(full)) return message;
        MutableComponent out = Component.empty();
        int[] at = {0};
        message.visit((style, text) -> {
            int end = Math.min(masked.length(), at[0] + text.length());
            out.append(Component.literal(masked.substring(at[0], end)).setStyle(style));
            at[0] = end;
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }
}
