package net.skykings.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings, saved in .minecraft/config/skykings-guild-bridge.json. */
public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("skykings-guild-bridge.json");
    private static BridgeConfig instance;

    /** Change this to use a different bridge server (e.g. one your guild hosts). */
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
