package net.skykings.bridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognises Hypixel guild chat and guild events in incoming chat lines. */
public final class GuildChatParser {
    private GuildChatParser() {}

    // Guild > [MVP+] Steve [Officer]: hello
    private static final Pattern CHAT = Pattern.compile(
            "^(Guild|Officer) > (?:\\[[A-Z+]+\\] )?([A-Za-z0-9_]{1,16})(?: \\[([^\\]]{1,32})\\])?: (.+)$");
    // Guild > Steve joined.
    private static final Pattern LOGIN = Pattern.compile("^Guild > ([A-Za-z0-9_]{1,16} (?:joined|left)\\.)$");
    // Whole-line guild events; the server re-checks these against its own list.
    private static final Pattern EVENT = Pattern.compile(
            "^(?:\\[[A-Z+]+\\] )?[A-Za-z0-9_]{1,16} (?:joined the guild!|left the guild!|was kicked from the guild by .+!|was (?:promoted|demoted) from .+ to .+)$"
                    + "|^The Guild has reached Level \\d{1,3}!$");

    public record ChatLine(boolean officer, String sender, String rank, String message) {}

    /** Removes Minecraft colour/format codes. */
    public static String strip(String text) {
        return text.replaceAll("§.", "").trim();
    }

    public static ChatLine parseChat(String line) {
        Matcher m = CHAT.matcher(line);
        if (!m.matches()) return null;
        return new ChatLine(m.group(1).equals("Officer"), m.group(2), m.group(3) == null ? "" : m.group(3), m.group(4));
    }

    /** The event text to report, or null. */
    public static String parseEvent(String line) {
        Matcher login = LOGIN.matcher(line);
        if (login.matches()) return login.group(1);
        return EVENT.matcher(line).matches() ? line : null;
    }
}
