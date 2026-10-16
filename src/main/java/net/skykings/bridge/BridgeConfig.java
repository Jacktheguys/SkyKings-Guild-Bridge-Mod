package net.skykings.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings, saved in .minecraft/config/skykings-guild-bridge.json. */
public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("skykings-guild-bridge.json");
    private static BridgeConfig instance;

    /** The bridge server. Only change it to a server you trust: it receives your guild chat and signs you in. */
    public String serverUrl = "wss://skykings.net/bridge/ws";
    public boolean enabled = true;
    public boolean showDiscord = true;
    public boolean showBridgeChat = true;
    public boolean showBotReplies = true;
    /** While guild chat is toggled off (/g toggle), hide Discord, bridge and bot lines too. */
    public boolean hideWhenGuildChatOff = true;
    /** Last known /g toggle state (Hypixel remembers it between sessions, so we do too). */
    public boolean guildChatVisible = true;
    /** Your name colour for other mod users (a ChatFormatting name like "gold"), or "none". */
    public String nameColor = "none";
    /** Text colour of [Discord], [Bridge] and [Bot] messages in your chat (colour names like "white"). */
    public String discordTextColor = "white";
    public String bridgeTextColor = "white";
    public String botTextColor = "yellow";
    /** Market tools: only items that sell at least this many a day (0, 1, 5, 20, 100 or 500). */
    public int marketMinSales = 1;
    /**
     * Your personal ignore list: Minecraft or Discord names whose Discord, /bc and guild chat
     * messages are hidden on your screen. Only you are affected; nothing is sent to the server.
     */
    public List<String> blocked = new ArrayList<>();
    /** Show the name colours players picked (and Discord role colours); off = plain names. */
    public boolean customColors = true;
    /** Your word filter: these words are shown as **** in guild chat and bridge messages. */
    public List<String> filteredWords = new ArrayList<>();

    public static final int MAX_BLOCKED = 200;
    public static final int MAX_FILTERED_WORDS = 200;

    /** Adds words to the filter (a phrase in quotes or several words); returns what to tell the player. */
    public String filterAdd(String word) {
        String w = cleanBlockName(word);
        if (w == null) return "Give a word (up to 32 characters).";
        w = w.toLowerCase(Locale.ROOT);
        if (filteredWords.contains(w)) return "\"" + w + "\" is already filtered.";
        if (filteredWords.size() >= MAX_FILTERED_WORDS) return "Your word filter is full (" + MAX_FILTERED_WORDS + " words).";
        filteredWords.add(w);
        filteredWords.sort(null);
        save();
        WordFilter.changed();
        return "Filtering \"" + w + "\": it shows as stars in guild chat and bridge messages.";
    }

    public String filterRemove(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        boolean removed = filteredWords.remove(w);
        save();
        WordFilter.changed();
        return removed ? "Stopped filtering \"" + w + "\"." : "\"" + w + "\" isn't filtered.";
    }

    /** Whether messages from `name` are hidden (names match ignoring case). */
    public boolean isBlocked(String name) {
        if (name == null || blocked == null || blocked.isEmpty()) return false;
        String n = name.trim();
        for (String b : blocked) if (b != null && b.equalsIgnoreCase(n)) return true;
        return false;
    }

    /** A name as it can go on the list, or null if it can't (empty, too long). */
    public static String cleanBlockName(String name) {
        if (name == null) return null;
        String n = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return n.isEmpty() || n.length() > 32 ? null : n;
    }

    /** Adds `name`; returns what to tell the player. */
    public String block(String name) {
        String n = cleanBlockName(name);
        if (n == null) return "Give a name (up to 32 characters).";
        if (isBlocked(n)) return n + " is already blocked.";
        if (blocked.size() >= MAX_BLOCKED) return "Your block list is full (" + MAX_BLOCKED + " names).";
        blocked.add(n);
        blocked.sort(String.CASE_INSENSITIVE_ORDER);
        save();
        return "Blocked " + n + ": you won't see their Discord, /bc or guild chat messages.";
    }

    /** Removes `name`; returns what to tell the player. */
    public String unblock(String name) {
        String n = name == null ? "" : name.trim();
        boolean removed = blocked.removeIf((b) -> b == null || b.toLowerCase(Locale.ROOT).equals(n.toLowerCase(Locale.ROOT)));
        save();
        return removed ? "Unblocked " + n + "." : n + " isn't blocked.";
    }

    /** Whether Discord/bridge/bot lines should be shown right now. */
    public boolean showBridgeLines() {
        return guildChatVisible || !hideWhenGuildChatOff;
    }

    public static BridgeConfig get() {
        if (instance == null) load();
        return instance;
    }

    public static void load() {
        BridgeConfig loaded = null;
        try {
            if (Files.exists(FILE)) loaded = GSON.fromJson(Files.readString(FILE), BridgeConfig.class);
        } catch (Exception e) {
            GuildBridgeMod.LOG.warn("Couldn't read {}, using defaults", FILE, e);
        }
        instance = loaded != null ? loaded : new BridgeConfig();
        // A hand-edited file may leave the list out or put odd entries in it.
        List<String> names = new ArrayList<>();
        if (instance.blocked != null) {
            for (String b : instance.blocked) {
                String n = cleanBlockName(b);
                if (n != null && names.size() < MAX_BLOCKED && names.stream().noneMatch(n::equalsIgnoreCase)) names.add(n);
            }
        }
        instance.blocked = names;
        List<String> words = new ArrayList<>();
        if (instance.filteredWords != null) {
            for (String w : instance.filteredWords) {
                String n = cleanBlockName(w);
                if (n != null && words.size() < MAX_FILTERED_WORDS && !words.contains(n.toLowerCase(Locale.ROOT))) words.add(n.toLowerCase(Locale.ROOT));
            }
        }
        instance.filteredWords = words;
        save();
    }

    public static void save() {
        try {
            Files.writeString(FILE, GSON.toJson(instance));
        } catch (IOException e) {
            GuildBridgeMod.LOG.warn("Couldn't save {}", FILE, e);
        }
    }

    /**
     * The server address, or null if it isn't safe to use. Guild chat only
     * travels encrypted (wss://); plain ws:// is only allowed to this computer,
     * for testing a local server.
     */
    public URI serverUri() {
        try {
            URI uri = URI.create(serverUrl.trim());
            String host = uri.getHost() == null ? "" : uri.getHost();
            if ("wss".equals(uri.getScheme()) && !host.isEmpty()) return uri;
            if ("ws".equals(uri.getScheme()) && (host.equals("localhost") || host.equals("127.0.0.1"))) return uri;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }
}
