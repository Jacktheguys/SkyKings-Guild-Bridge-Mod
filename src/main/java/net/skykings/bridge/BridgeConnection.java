package net.skykings.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The connection to the bridge server.
 *
 * Signing in works like joining a Minecraft server: the mod asks Mojang to
 * note that this account is joining a one-time "server id", and the bridge
 * server asks Mojang to confirm it. Your login token is only ever sent to
 * Mojang (by Minecraft's own code), never to the bridge server.
 */
public final class BridgeConnection implements WebSocket.Listener {
    // Must match the bridge server (it builds the same server id to check with Mojang).
    private static final String ID_PREFIX = "skykings-guild-bridge-v1";
    private static final Pattern NONCE = Pattern.compile("^[0-9a-f]{32}$");
    private static final Gson GSON = new Gson();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SkyKings Guild Bridge");
        t.setDaemon(true);
        return t;
    });
    private final SecureRandom random = new SecureRandom();
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;
    private volatile boolean connecting;
    private volatile boolean wanted;      // should we be connected right now?
    private volatile boolean ready;       // signed in
    private volatile boolean configured;  // guild linked to Discord
    private volatile String closeReason;
    private CompletableFuture<WebSocket> sending = CompletableFuture.completedFuture(null);
    private int failures;

    public boolean isReady() {
        return ready;
    }

    public boolean isConfigured() {
        return configured;
    }

    // What the settings screen shows (from the last "ready" / "online" message).
    private volatile boolean staff;
    private volatile String guildName = "";
    private volatile java.util.List<String> onlinePlayers = java.util.List.of();
    private volatile boolean printNextOnlineList;

    public boolean isStaff() {
        return ready && staff;
    }

    public String guildName() {
        return guildName;
    }

    public java.util.List<String> onlinePlayers() {
        return onlinePlayers;
    }

    /** Ask the server who's online; print = also show the list in chat (/gbridge online). */
    public void requestOnline(boolean print) {
        printNextOnlineList = print;
        send("online", Map.of());
    }

    public synchronized void connect() {
        wanted = true;
        if (socket != null || connecting) return;
        URI uri = BridgeConfig.get().serverUri();
        if (uri == null) {
            ChatDisplay.system("The server address in config/skykings-guild-bridge.json must start with wss://");
            wanted = false;
            return;
        }
        closeReason = null;
        connecting = true;
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, this)
                .whenComplete((ws, error) -> {
                    synchronized (this) {
                        connecting = false;
                    }
                    if (error != null) {
                        GuildBridgeMod.LOG.info("Bridge connection failed: {}", error.toString());
                        scheduleReconnect();
                    } else if (!wanted) {
                        ws.abort(); // disconnect() was called while connecting
                    }
                });
    }

    /** Events from a socket we've already replaced or closed are ignored. */
    private boolean isCurrent(WebSocket webSocket) {
        return webSocket == socket;
    }

    public synchronized void disconnect() {
        wanted = false;
        WebSocket ws = socket;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        reset();
    }

    public synchronized void reconnect() {
        disconnect();
        failures = 0;
        connect();
    }

    private synchronized void reset() {
        socket = null;
        ready = false;
        configured = false;
        partial.setLength(0);
        StaffTasks.clear();
    }

    private synchronized void scheduleReconnect() {
        reset();
        if (!wanted) return;
        failures++;
        // 5s, 10s, 20s ... up to 5 minutes; longer still if the server refused us.
        long delay = closeReason != null ? 300 : Math.min(300, 5L << Math.min(failures - 1, 6));
        scheduler.schedule(() -> {
            if (wanted) connect();
        }, delay, TimeUnit.SECONDS);
    }

    /** Send a message to the server (thread-safe; ignored if not signed in, except during sign-in). */
    public void send(String type, Map<String, ?> fields) {
        JsonObject json = GSON.toJsonTree(fields).getAsJsonObject();
        json.addProperty("type", type);
        sendRaw(json);
    }

    private synchronized void sendRaw(JsonObject json) {
        WebSocket ws = socket;
        if (ws == null) return;
        String text = json.toString();
        sending = sending.handle((ok, err) -> null).thenCompose(ignored -> ws.sendText(text, true));
    }

    // ── WebSocket.Listener ──────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket webSocket) {
        synchronized (this) {
            socket = webSocket;
        }
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (!isCurrent(webSocket)) return null;
        partial.append(data);
        if (partial.length() > 64 * 1024) {
            webSocket.abort();
            return null;
        }
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                handle(JsonParser.parseString(text).getAsJsonObject());
            } catch (Exception e) {
                GuildBridgeMod.LOG.warn("Bad message from bridge server", e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (!isCurrent(webSocket)) return null;
        if (statusCode == 4000 && reason != null && !reason.isEmpty()) {
            closeReason = reason;
            ChatDisplay.system("Disconnected: " + reason);
        } else if (ready) {
            ChatDisplay.system("Lost connection to the bridge, reconnecting...");
        }
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (!isCurrent(webSocket)) return;
        GuildBridgeMod.LOG.info("Bridge connection error: {}", error.toString());
        scheduleReconnect();
    }

    // ── Messages from the server ────────────────────────────────────────

    /** A Discord role colour sent as "rgb" (0 or missing = none). */
    private static Integer rgbField(JsonObject json) {
        JsonElement e = json.get("rgb");
        if (e == null || e.isJsonNull()) return null;
        int rgb = e.getAsInt() & 0xFFFFFF;
        return rgb == 0 ? null : rgb;
    }

    private static String str(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private void handle(JsonObject msg) {
        String type = str(msg, "type");
        if (type == null) return;
        BridgeConfig cfg = BridgeConfig.get();
        switch (type) {
            case "challenge" -> signIn(str(msg, "nonce"));
            case "ready" -> onReady(msg);
            case "display" -> {
                String kind = String.valueOf(str(msg, "kind"));
                String author = str(msg, "author");
                String message = str(msg, "message");
                boolean show = cfg.showBridgeLines(); // hidden while guild chat is toggled off
                switch (kind) {
                    case "discord" -> { if (show && cfg.showDiscord) ChatDisplay.message("Discord", ChatFormatting.BLUE, author, rgbField(msg), message); }
                    case "discord_officer" -> { if (show && cfg.showDiscord) ChatDisplay.message("Discord Officer", ChatFormatting.DARK_AQUA, author, message); }
                    case "bridge" -> { if (show && cfg.showBridgeChat) ChatDisplay.message("Bridge", ChatFormatting.AQUA, author, NameColors.rgb(str(msg, "color")), message); }
                    case "bot" -> { if (show && cfg.showBotReplies) ChatDisplay.message("Bot", ChatFormatting.GOLD, "SkyKings", message); }
                    default -> ChatDisplay.system(message);
                }
            }
            case "online" -> {
                JsonArray players = msg.has("players") ? msg.getAsJsonArray("players") : new JsonArray();
                java.util.List<String> names = new java.util.ArrayList<>();
                players.forEach((p) -> names.add(ChatDisplay.safe(p.getAsString(), 16)));
                onlinePlayers = names;
                if (printNextOnlineList) {
                    printNextOnlineList = false;
                    ChatDisplay.system(names.isEmpty() ? "Nobody is online with the mod." : "Online with the mod (" + names.size() + "): " + String.join(", ", names));
                }
            }
            case "colors" -> {
                Map<String, String> colors = new HashMap<>();
                if (msg.has("colors") && msg.get("colors").isJsonObject()) {
                    msg.getAsJsonObject("colors").entrySet().forEach((e) ->
                            colors.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString()));
                }
                NameColors.update(colors, msg.has("replace") && msg.get("replace").getAsBoolean());
            }
            case "task" -> StaffTasks.add(new StaffTasks.Task(
                    msg.get("id").getAsLong(), str(msg, "action"), str(msg, "target"), str(msg, "extra"), str(msg, "requestedBy")));
            case "task_claimed" -> StaffTasks.claimed(msg.get("id").getAsLong());
            case "task_gone" -> StaffTasks.remove(msg.get("id").getAsLong());
            case "error" -> ChatDisplay.system(String.valueOf(str(msg, "message")));
            default -> { }
        }
    }

    private void onReady(JsonObject msg) {
        boolean firstTime = !ready;
        ready = true;
        failures = 0;
        configured = msg.has("configured") && msg.get("configured").getAsBoolean();
        staff = msg.has("staff") && msg.get("staff").getAsBoolean();
        guildName = ChatDisplay.safe(str(msg, "guild"), 40);
        JsonArray players = msg.has("online") ? msg.getAsJsonArray("online") : new JsonArray();
        java.util.List<String> names = new java.util.ArrayList<>();
        players.forEach((p) -> names.add(ChatDisplay.safe(p.getAsString(), 16)));
        onlinePlayers = names;
        if (!firstTime) return;
        String chosen = BridgeConfig.get().nameColor;
        if (chosen != null && !"none".equals(chosen)) send("set_color", Map.of("color", chosen));
        String guild = guildName;
        if (!configured) {
            ChatDisplay.system("Signed in, but " + guild + " isn't linked to Discord yet. "
                    + "A Discord admin runs /bridge setup, then the Guild Master runs /gbridge claim <code>.");
            return;
        }
        JsonArray online = msg.has("online") ? msg.getAsJsonArray("online") : new JsonArray();
        ChatDisplay.system("Connected to " + guild + "'s bridge (" + online.size() + " online with the mod). /gbridge help");
    }

    private void signIn(String serverNonce) {
        if (serverNonce == null || !NONCE.matcher(serverNonce).matches()) {
            WebSocket ws = socket;
            if (ws != null) ws.abort();
            return;
        }
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String clientNonce = HexFormat.of().formatHex(bytes);
        String serverId = serverIdFor(serverNonce, clientNonce);

        // joinServer is a blocking web request to Mojang: keep it off the game and socket threads.
        scheduler.execute(() -> {
            Minecraft client = Minecraft.getInstance();
            User user = client.getUser();
            try {
                MojangJoin.joinServer(client, user.getProfileId(), user.getAccessToken(), serverId);
            } catch (Exception e) {
                GuildBridgeMod.LOG.warn("Mojang sign-in for the bridge failed", e);
                ChatDisplay.system("Couldn't confirm your account with Mojang, will retry.");
                WebSocket ws = socket;
                if (ws != null) ws.abort();
                return;
            }
            Map<String, Object> auth = new HashMap<>();
            auth.put("username", user.getName());
            auth.put("clientNonce", clientNonce);
            auth.put("version", GuildBridgeMod.version());
            auth.put("guildChatVisible", BridgeConfig.get().guildChatVisible);
            // So Discord can show whether you'll see messages typed there.
            auth.put("showDiscord", BridgeConfig.get().showDiscord);
            auth.put("hideWhenGuildChatOff", BridgeConfig.get().hideWhenGuildChatOff);
            send("auth", auth);
        });
    }

    static String serverIdFor(String serverNonce, String clientNonce) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((ID_PREFIX + "|" + serverNonce + "|" + clientNonce).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
