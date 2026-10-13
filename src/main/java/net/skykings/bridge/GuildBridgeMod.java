package net.skykings.bridge;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * SkyKings Guild Bridge.
 *
 * What it does on Hypixel:
 *  - reads guild chat you already receive and passes it to your guild's Discord channel;
 *  - shows Discord messages in your chat (only on your screen);
 *  - shows guild staff the tasks asked for on Discord, to run with a click.
 * It never sends chat or commands to Hypixel on its own: staff tasks only run
 * when a staff member clicks [Run] and presses Enter.
 */
public final class GuildBridgeMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("SkyKings Guild Bridge");
    private static final BridgeConnection CONNECTION = new BridgeConnection();
    private static boolean onHypixel;
    private static boolean openScreenNextTick;
    private static MediaStore.Loaded openImageNextTick;
    private static ProfileScreen openGearNextTick;
    private static MarketScreen openMarketNextTick;
    private static long lastMarketCommand;
    private static long guildToggleSentAt; // when the player last sent /g toggle

    public static BridgeConnection connection() {
        return CONNECTION;
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer("skykings-guild-bridge")
                .map((mod) -> mod.getMetadata().getVersion().getFriendlyString()).orElse("0");
    }

    public static void connectIfOnHypixel() {
        if (onHypixel && BridgeConfig.get().enabled) CONNECTION.connect();
    }

    /** Your name colour for other mod users ("none" to clear). */
    public static boolean setNameColor(String color) {
        String c = color.trim().toLowerCase();
        if (!NameColors.CHOICES.contains(c) && !NameColors.isHex(c)) return false;
        if (NameColors.isHex(c) && !CONNECTION.isPatron()) {
            ChatDisplay.premiumNotice();
            return true; // handled: told them about premium
        }
        BridgeConfig.get().nameColor = c;
        BridgeConfig.save();
        CONNECTION.send("set_color", Map.of("color", c));
        return true;
    }

    private static int setNameColor(FabricClientCommandSource source, String color) {
        if (NameColors.isHex(color.trim()) && !CONNECTION.isPatron()) {
            ChatDisplay.premiumNotice();
            return 1;
        }
        if (!setNameColor(color)) return reply(source, "Colours: " + String.join(", ", NameColors.CHOICES) + ", or #rrggbb (patrons)");
        return reply(source, "none".equals(color.toLowerCase()) ? "Name colour cleared." : "Your name colour is now " + color.toLowerCase() + ".");
    }

    /** Text colour of [Discord], [Bridge] or [Bot] messages (only on your screen). */
    private static int setTextColor(FabricClientCommandSource source, String type, String color) {
        String c = color.trim().toLowerCase();
        if (NameColors.rgb(c) == null) return reply(source, "Colours: " + String.join(", ", NameColors.CHOICES.subList(1, NameColors.CHOICES.size())) + ", or #rrggbb (patrons)");
        if (NameColors.isHex(c) && !CONNECTION.isPatron()) {
            ChatDisplay.premiumNotice();
            return 1;
        }
        BridgeConfig cfg = BridgeConfig.get();
        switch (type.toLowerCase()) {
            case "discord" -> cfg.discordTextColor = c;
            case "bridge" -> cfg.bridgeTextColor = c;
            case "bot" -> cfg.botTextColor = c;
            default -> {
                return reply(source, "Use discord, bridge or bot.");
            }
        }
        BridgeConfig.save();
        return reply(source, type.toLowerCase() + " messages are now " + c + ".");
    }

    /** Settings the server uses: whether you see Discord messages (shown next to your name there). */
    public static void sendPrefs() {
        BridgeConfig cfg = BridgeConfig.get();
        CONNECTION.send("prefs", Map.of("showDiscord", cfg.showDiscord, "hideWhenGuildChatOff", cfg.hideWhenGuildChatOff));
    }

    /** Guild chat toggled (/g toggle on Hypixel, or the settings screen). */
    public static void setGuildChatVisible(boolean visible) {
        BridgeConfig cfg = BridgeConfig.get();
        if (cfg.guildChatVisible == visible) return;
        cfg.guildChatVisible = visible;
        BridgeConfig.save();
        CONNECTION.send("chat_visibility", Map.of("visible", visible));
        if (cfg.hideWhenGuildChatOff) {
            ChatDisplay.system(visible ? "Guild chat is on: showing Discord messages again."
                    : "Guild chat is off: Discord messages are hidden too (your guild still sees everything in Discord).");
        }
    }

    @Override
    public void onInitializeClient() {
        BridgeConfig.load();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData server = client.getCurrentServer();
            onHypixel = server != null && isHypixel(server.ip);
            if (onHypixel && BridgeConfig.get().enabled) CONNECTION.connect();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            onHypixel = false;
            CONNECTION.disconnect();
        });

        // Members' chosen name colours, in guild chat.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
                overlay || !onHypixel ? message : NameColors.recolor(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && onHypixel) onChatLine(GuildChatParser.strip(message.getString()));
        });
        // Hypixel sends guild chat as system messages, but in case a line ever arrives as player chat:
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receivedAt) -> {
            if (onHypixel) onChatLine(GuildChatParser.strip(message.getString()));
        });
        ClientSendMessageEvents.COMMAND.register(GuildBridgeMod::onCommandSent);
        ChatHover.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StaffTasks.tick();
            EmojiFont.tick();
            if (guildToggleSentAt != 0 && System.currentTimeMillis() - guildToggleSentAt > 3000) {
                // Hypixel's reply didn't say on or off: it flips, so flip.
                guildToggleSentAt = 0;
                setGuildChatVisible(!BridgeConfig.get().guildChatVisible);
            }
            if (openMarketNextTick != null) {
                ScreenAccess.setScreen(client, openMarketNextTick);
                openMarketNextTick = null;
            }
            if (openGearNextTick != null) {
                ScreenAccess.setScreen(client, openGearNextTick);
                openGearNextTick = null;
            }
            if (openImageNextTick != null) {
                ScreenAccess.setScreen(client, new ImageScreen(openImageNextTick));
                openImageNextTick = null;
            }
            if (openScreenNextTick) {
                openScreenNextTick = false;
                ScreenAccess.setScreen(client, new BridgeScreen());
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("gbridge")
                    .executes(ctx -> openSettings())
                    .then(literal("settings").executes(ctx -> openSettings()))
                    .then(literal("help").executes(ctx -> help(ctx.getSource())))
                    .then(literal("status").executes(ctx -> status(ctx.getSource())))
                    .then(literal("toggle").executes(ctx -> toggle(ctx.getSource())))
                    .then(literal("reconnect").executes(ctx -> {
                        CONNECTION.reconnect();
                        return reply(ctx.getSource(), "Reconnecting...");
                    }))
                    .then(literal("online").executes(ctx -> {
                        if (!CONNECTION.isReady()) return reply(ctx.getSource(), "Not connected to the bridge" + (onHypixel ? " yet." : " (only works on Hypixel)."));
                        CONNECTION.requestOnline(true);
                        return 1;
                    }))
                    .then(literal("tasks").executes(ctx -> listTasks(ctx.getSource())))
                    .then(literal("show").then(argument("what", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (String s : new String[]{"discord", "bridge", "bot"}) builder.suggest(s);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> toggleShow(ctx.getSource(), StringArgumentType.getString(ctx, "what")))))
                    .then(literal("colour").then(argument("colour", StringArgumentType.greedyString())
                            .suggests((ctx, builder) -> {
                                NameColors.CHOICES.forEach(builder::suggest);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> setNameColor(ctx.getSource(), StringArgumentType.getString(ctx, "colour")))))
                    .then(literal("textcolour").then(argument("type", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (String s : new String[]{"discord", "bridge", "bot"}) builder.suggest(s);
                                return builder.buildFuture();
                            })
                            .then(argument("colour", StringArgumentType.greedyString())
                                    .suggests((ctx, builder) -> {
                                        NameColors.CHOICES.subList(1, NameColors.CHOICES.size()).forEach(builder::suggest);
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> setTextColor(ctx.getSource(), StringArgumentType.getString(ctx, "type"),
                                            StringArgumentType.getString(ctx, "colour"))))))
                    .then(literal("view").then(argument("id", IntegerArgumentType.integer(1))
                            .executes(ctx -> viewMedia(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                    .then(literal("claim").then(argument("code", StringArgumentType.word())
                            .executes(ctx -> sendIfReady(ctx, "claim_guild", Map.of("code", StringArgumentType.getString(ctx, "code"))))))
                    .then(literal("run").then(argument("id", LongArgumentType.longArg(1))
                            .executes(ctx -> taskAction(ctx, "task_claim"))))
                    .then(literal("dismiss").then(argument("id", LongArgumentType.longArg(1))
                            .executes(ctx -> taskAction(ctx, "task_cancel")))));
            // /gpv [player] [profile]: skykings.net/profilestats in-game (fetched through the bridge).
            dispatcher.register(literal("gpv")
                    .executes(ctx -> openGear(ctx.getSource(), Minecraft.getInstance().getUser().getName(), null))
                    .then(argument("player", StringArgumentType.word())
                            .executes(ctx -> openGear(ctx.getSource(), StringArgumentType.getString(ctx, "player"), null))
                            .then(argument("profile", StringArgumentType.word())
                                    .executes(ctx -> openGear(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "profile"))))));
            // Market tools (data from skykings.net through the bridge).
            dispatcher.register(literal("gscanner").executes(ctx -> openMarket("snipes")));
            dispatcher.register(literal("gbazaar").executes(ctx -> openMarket("bazaar")));
            dispatcher.register(literal("gmarket")
                    .executes(ctx -> openMarket("snipes"))
                    .then(argument("tool", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (String t : MarketScreen.TOOLS) builder.suggest(t);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> openMarket(StringArgumentType.getString(ctx, "tool")))));
            dispatcher.register(literal("bc").then(argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> sendIfReady(ctx, "bridge_chat", Map.of("message", StringArgumentType.getString(ctx, "message"))))));
        });
    }

    private static boolean isHypixel(String address) {
        String host = address.toLowerCase().split(":")[0];
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }

    /** The settings screen opens on the next tick (the chat box closes right after a command). */
    /** Opens a Discord image or emoji someone clicked in chat. */
    private static int viewMedia(FabricClientCommandSource source, int id) {
        MediaStore.Loaded image = MediaStore.open(id);
        if (image == null) return reply(source, "That picture is too old to open now.");
        openImageNextTick = image;
        return 1;
    }

    private static int openGear(FabricClientCommandSource source, String player, String profile) {
        if (!player.matches("[A-Za-z0-9_]{1,16}")) return reply(source, "That isn't a Minecraft name.");
        if (profile != null && !profile.matches("[A-Za-z]{1,20}")) return reply(source, "That isn't a profile name (like Peach or Apple).");
        openGearNextTick = ProfileScreen.load(player, profile);
        return 1;
    }

    private static int openMarket(String tool) {
        openMarketNextTick = MarketScreen.load(tool);
        return 1;
    }

    /**
     * A market row was clicked: open that auction (/viewauction) or search the Bazaar (/bz).
     * The command is built here from a checked auction id or item name; nothing from the
     * server is run as a command. Only on Hypixel, and at most one a second.
     */
    static boolean runMarketAction(String type, String value) {
        Minecraft client = Minecraft.getInstance();
        if (!onHypixel || client.player == null) {
            ChatDisplay.system("That works on Hypixel.");
            return false;
        }
        String command;
        if ("auction".equals(type) && value != null && value.matches("[0-9a-f]{32}")) command = "viewauction " + value;
        else if ("bazaar".equals(type) && value != null && value.matches("[A-Za-z0-9 '+:.()-]{1,40}")) command = "bz " + value.trim();
        else return false;
        long now = System.currentTimeMillis();
        if (now - lastMarketCommand < 1000) return false;
        lastMarketCommand = now;
        client.player.connection.sendCommand(command);
        return true;
    }

    private static int openSettings() {
        if (CONNECTION.isReady()) CONNECTION.requestOnline(false); // fresh "online" count for the screen
        openScreenNextTick = true;
        return 1;
    }

    /** Notice /g toggle, to hide Discord messages while guild chat is off. */
    private static void onCommandSent(String command) {
        String c = command.trim().toLowerCase();
        if (onHypixel && (c.equals("g toggle") || c.equals("guild toggle"))) guildToggleSentAt = System.currentTimeMillis();
    }

    /** Every chat line while on Hypixel: forward guild chat and guild events. */
    private static void onChatLine(String line) {
        if (guildToggleSentAt != 0 && line.toLowerCase().contains("guild chat") && !line.startsWith("Guild >") && !line.startsWith("Officer >")) {
            String l = line.toLowerCase();
            if (l.contains("disabled") || l.contains(" off")) { guildToggleSentAt = 0; setGuildChatVisible(false); }
            else if (l.contains("enabled") || l.contains(" on")) { guildToggleSentAt = 0; setGuildChatVisible(true); }
        }
        StaffTasks.onChatLine(line);
        if (!CONNECTION.isReady() || !CONNECTION.isConfigured()) return;

        GuildChatParser.ChatLine chat = GuildChatParser.parseChat(line);
        if (chat != null) {
            CONNECTION.send("guild_chat", Map.of(
                    "officer", chat.officer(), "sender", chat.sender(), "rank", chat.rank(), "message", chat.message()));
            return;
        }
        String event = GuildChatParser.parseEvent(line);
        if (event != null) CONNECTION.send("guild_event", Map.of("text", event));
    }

    // ── Commands ────────────────────────────────────────────────────────

    private static int reply(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[Bridge] ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.literal(message).withStyle(ChatFormatting.GRAY)));
        return 1;
    }

    private static int sendIfReady(CommandContext<FabricClientCommandSource> ctx, String type, Map<String, ?> fields) {
        if (!CONNECTION.isReady()) return reply(ctx.getSource(), "Not connected to the bridge" + (onHypixel ? " yet." : " (only works on Hypixel)."));
        CONNECTION.send(type, fields);
        return 1;
    }

    private static int taskAction(CommandContext<FabricClientCommandSource> ctx, String type) {
        long id = LongArgumentType.getLong(ctx, "id");
        if (!StaffTasks.isOpen(id)) return reply(ctx.getSource(), "That task is already done or was taken by someone else.");
        if (type.equals("task_claim")) StaffTasks.askedToClaim(id); // only a claim we asked for runs a command
        return sendIfReady(ctx, type, Map.of("id", id));
    }

    private static int help(FabricClientCommandSource source) {
        reply(source, "Guild chat is shared with your guild's Discord. Commands:");
        for (String line : new String[]{
                "/gbridge - settings and every command, on one screen",
                "/gbridge colour <colour> - your name colour for other mod users (#rrggbb for patrons)",
                "/gbridge textcolour <discord|bridge|bot> <colour> - message text colours",
                "/bc <message> - talk to Discord and mod users only (not in guild chat)",
                "/gpv [player] [profile] - a player's profile stats, like skykings.net/profilestats",
                "/gscanner - auction snipes, /gbazaar - bazaar flips, /gmarket - every market tool",
                "/gbridge status - connection status",
                "/gbridge online - who's online with the mod",
                "/gbridge show <discord|bridge|bot> - hide or show those messages",
                "/gbridge toggle - turn the bridge off or on",
                "/gbridge tasks - staff tasks waiting for you",
                "/gbridge claim <code> - Guild Master: link the guild to Discord",
                "/gbridge reconnect"}) {
            reply(source, line);
        }
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        BridgeConfig cfg = BridgeConfig.get();
        String state = !cfg.enabled ? "turned off (/gbridge toggle)"
                : !onHypixel ? "waiting until you join Hypixel"
                : !CONNECTION.isReady() ? "connecting..."
                : !CONNECTION.isConfigured() ? "signed in, but your guild isn't linked to Discord yet"
                : "connected";
        return reply(source, "Bridge: " + state);
    }

    private static int toggle(FabricClientCommandSource source) {
        BridgeConfig cfg = BridgeConfig.get();
        cfg.enabled = !cfg.enabled;
        BridgeConfig.save();
        if (cfg.enabled && onHypixel) CONNECTION.connect();
        if (!cfg.enabled) CONNECTION.disconnect();
        return reply(source, cfg.enabled ? "Bridge turned on." : "Bridge turned off.");
    }

    private static int toggleShow(FabricClientCommandSource source, String what) {
        BridgeConfig cfg = BridgeConfig.get();
        Consumer<Boolean> set;
        boolean now;
        switch (what.toLowerCase()) {
            case "discord" -> { now = !cfg.showDiscord; set = v -> cfg.showDiscord = v; }
            case "bridge" -> { now = !cfg.showBridgeChat; set = v -> cfg.showBridgeChat = v; }
            case "bot" -> { now = !cfg.showBotReplies; set = v -> cfg.showBotReplies = v; }
            default -> {
                return reply(source, "Use discord, bridge or bot.");
            }
        }
        set.accept(now);
        BridgeConfig.save();
        sendPrefs();
        return reply(source, (now ? "Showing " : "Hiding ") + what.toLowerCase() + " messages.");
    }

    private static int listTasks(FabricClientCommandSource source) {
        var tasks = StaffTasks.all();
        if (tasks.isEmpty()) return reply(source, "No staff tasks waiting.");
        Minecraft.getInstance().execute(() -> tasks.forEach(task -> {
            StaffTasks.remove(task.id());
            StaffTasks.add(task); // show it again, with its buttons
        }));
        return 1;
    }
}
