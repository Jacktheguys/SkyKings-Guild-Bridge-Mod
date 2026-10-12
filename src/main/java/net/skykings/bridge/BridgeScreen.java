package net.skykings.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The settings screen (/gbridge): a dark panel with tabs for settings (toggle
 * switches), who's online, and every command. Everything is drawn with plain
 * shapes and text, which work the same on every supported Minecraft version.
 */
public final class BridgeScreen extends Screen {
    // Colours (ARGB)
    private static final int PANEL = 0xF0121318;
    private static final int PANEL_EDGE = 0xFF2A2D37;
    private static final int ROW_HOVER = 0x14FFFFFF;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9097A3;
    private static final int FAINT = 0xFF5C626D;
    private static final int ACCENT = 0xFF5AA9FF;
    private static final int ACCENT_2 = 0xFF7C5CFF;
    private static final int GREEN = 0xFF3DDC84;
    private static final int RED = 0xFFF2545B;
    private static final int AMBER = 0xFFF5B942;
    private static final int TRACK_OFF = 0xFF3A3E49;

    private static final String[] TABS = {"Settings", "Online", "Commands"};
    private static final int ROW_H = 30;

    private record Toggle(String name, String description, BooleanSupplier get, Consumer<Boolean> set,
                          BooleanSupplier usable, String unusableNote) {}

    private final List<Toggle> toggles = new ArrayList<>();
    private final Map<Toggle, Float> knob = new HashMap<>(); // 0 = off .. 1 = on, animated
    private int tab = 0;
    private double scroll = 0;
    private long lastFrame = System.nanoTime();
    private long lastOnlineRequest = 0;
    private long lastEventClick = 0;      // when a normal click event last arrived
    private boolean buttonWasDown = true; // assume held until seen released (no click on open)
    private static boolean loggedFallback;
    private boolean eventSinceRelease;    // a normal click event arrived during this press

    // Layout, worked out each frame (the window can be resized)
    private int px, py, pw, ph, contentTop, contentBottom;

    public BridgeScreen() {
        super(Component.literal("SkyKings Guild Bridge"));
        BridgeConfig cfg = BridgeConfig.get();
        BridgeConnection connection = GuildBridgeMod.connection();
        toggles.add(new Toggle("Bridge", "Connect to your guild's Discord on Hypixel",
                () -> cfg.enabled, (v) -> {
                    cfg.enabled = v;
                    if (v) GuildBridgeMod.connectIfOnHypixel();
                    else connection.disconnect();
                }, () -> true, null));
        toggles.add(new Toggle("Discord messages", "Show what's said in your Discord channel",
                () -> cfg.showDiscord, (v) -> cfg.showDiscord = v, () -> true, null));
        toggles.add(new Toggle("Bridge chat", "Show /bc messages from other mod users",
                () -> cfg.showBridgeChat, (v) -> cfg.showBridgeChat = v, () -> true, null));
        toggles.add(new Toggle("Bot replies", "Show answers to !nw, !lvl and the rest",
                () -> cfg.showBotReplies, (v) -> cfg.showBotReplies = v, () -> true, null));
        toggles.add(new Toggle("Hide with /g toggle", "Hide Discord messages while guild chat is off",
                () -> cfg.hideWhenGuildChatOff, (v) -> cfg.hideWhenGuildChatOff = v, () -> true, null));
        toggles.add(new Toggle("Guild chat shown", "Follows /g toggle by itself; fix it here if wrong",
                () -> cfg.guildChatVisible, GuildBridgeMod::setGuildChatVisible, () -> true, null));
        for (Toggle t : toggles) knob.put(t, t.get().getAsBoolean() ? 1f : 0f);
    }

    @Override
    protected void init() {
        super.init();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.mouseHandler.isMouseGrabbed()) minecraft.mouseHandler.releaseMouse();
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        layout();
        pollClick(mouseX, mouseY);

        // Keep the online list fresh while it's showing.
        BridgeConnection connection = GuildBridgeMod.connection();
        if (tab == 1 && connection.isReady() && System.currentTimeMillis() - lastOnlineRequest > 5000) {
            lastOnlineRequest = System.currentTimeMillis();
            connection.requestOnline(false);
        }

        // Panel: soft shadow, thin edge, accent strip along the top.
        roundRect(g, px - 2, py + 3, pw + 4, ph + 2, 9, 0x50000000);
        roundRect(g, px - 1, py - 1, pw + 2, ph + 2, 9, PANEL_EDGE);
        roundRect(g, px, py, pw, ph, 8, PANEL);
        g.fillGradient(px + 8, py, px + pw - 8, py + 2, ACCENT, ACCENT_2);

        // Header: title, version, status pill.
        g.text(font, Component.literal("SkyKings Guild Bridge").withStyle((s) -> s.withBold(true)), px + 14, py + 12, TEXT, false);
        String version = "v" + GuildBridgeMod.version();
        g.text(font, version, px + pw - 14 - font.width(version), py + 12, FAINT, false);
        drawStatus(g, connection, px + 14, py + 27);

        // Tabs
        int tx = px + 14;
        int tabY = py + 46;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]);
            boolean hover = inside(mouseX, mouseY, tx - 4, tabY - 4, w + 8, 16);
            g.text(font, TABS[i], tx, tabY, i == tab ? TEXT : hover ? 0xFFC9CDD4 : MUTED, false);
            if (i == tab) g.fill(tx, tabY + 11, tx + w, tabY + 13, ACCENT);
            tx += w + 18;
        }
        g.fill(px + 1, tabY + 13, px + pw - 1, tabY + 14, 0xFF22252D);

        // Content (clipped, scrollable)
        g.enableScissor(px + 1, contentTop, px + pw - 1, contentBottom);
        int y = contentTop + 6 - (int) scroll;
        int contentHeight = switch (tab) {
            case 0 -> drawSettings(g, y, mouseX, mouseY, dt);
            case 1 -> drawOnline(g, y, connection);
            default -> drawCommands(g, y);
        };
        g.disableScissor();
        clampScroll(contentHeight);
        drawScrollbar(g, contentHeight);

        // Footer buttons
        g.fill(px + 1, contentBottom, px + pw - 1, contentBottom + 1, 0xFF22252D);
        pill(g, "Done", doneX(), footerY(), 52, true, inside(mouseX, mouseY, doneX(), footerY(), 52, 18));
        pill(g, "Reconnect", reconnectX(), footerY(), 70, false, inside(mouseX, mouseY, reconnectX(), footerY(), 70, 18));
    }

    private void drawStatus(GuiGraphicsExtractor g, BridgeConnection connection, int x, int y) {
        BridgeConfig cfg = BridgeConfig.get();
        int color;
        String text;
        if (!cfg.enabled) {
            color = FAINT;
            text = "Turned off";
        } else if (!connection.isReady()) {
            color = RED;
            text = "Not connected (works on Hypixel)";
        } else if (!connection.isConfigured()) {
            color = AMBER;
            text = connection.guildName() + " isn't linked to Discord yet";
        } else {
            color = GREEN;
            int n = connection.onlinePlayers().size();
            text = "Connected to " + connection.guildName() + "  ·  " + n + " online" + (connection.isStaff() ? "  ·  staff" : "");
        }
        text = ellipsize(text, pw - 50);
        int w = font.width(text) + 20;
        roundRect(g, x, y - 3, w, 14, 7, (color & 0x00FFFFFF) | 0x26000000);
        roundRect(g, x + 6, y + 1, 5, 5, 2, color);
        g.text(font, text, x + 14, y, color, false);
    }

    private int drawSettings(GuiGraphicsExtractor g, int top, int mouseX, int mouseY, float dt) {
        int y = top;
        for (Toggle t : toggles) {
            boolean usable = t.usable().getAsBoolean();
            boolean on = t.get().getAsBoolean();
            boolean hover = usable && inContent(mouseY) && inside(mouseX, mouseY, px + 6, y, pw - 12, ROW_H - 2);
            if (hover) roundRect(g, px + 6, y, pw - 12, ROW_H - 2, 5, ROW_HOVER);

            g.text(font, t.name(), px + 14, y + 5, usable ? TEXT : FAINT, false);
            String desc = usable ? t.description() : t.unusableNote();
            int descColor = usable ? MUTED : FAINT;
            g.text(font, ellipsize(desc, pw - 72), px + 14, y + 16, descColor, false);

            // Switch: a knob sliding over a track that fades from grey to green.
            float k = knob.getOrDefault(t, 0f);
            k = Math.max(0f, Math.min(1f, k + (on ? 1 : -1) * dt * 8f));
            knob.put(t, k);
            int sx = px + pw - 14 - 26;
            int sy = y + 7;
            roundRect(g, sx, sy, 26, 14, 7, usable ? blend(TRACK_OFF, GREEN, k) : 0xFF2A2D35);
            roundRect(g, sx + 2 + Math.round(k * 12), sy + 2, 10, 10, 5, usable ? 0xFFFFFFFF : 0xFF6A6F78);
            y += ROW_H;
        }
        return y - top + drawColors(g, y, mouseX, mouseY) + 4;
    }

    private static final int SWATCH = 12;
    private static final int SWATCH_GAP = 6;

    /** A colour picker row: name, description, and swatches (the chosen one has a white ring). */
    private record ColorRow(String name, String description, List<String> choices,
                            java.util.function.Supplier<String> get, Consumer<String> set) {}

    private List<ColorRow> colorRows() {
        BridgeConfig cfg = BridgeConfig.get();
        List<String> textChoices = NameColors.CHOICES.subList(1, NameColors.CHOICES.size()); // no "none"
        return List.of(
                new ColorRow("Name colour", "How other mod users see your name", NameColors.CHOICES,
                        () -> cfg.nameColor, (c) -> GuildBridgeMod.setNameColor(c)),
                new ColorRow("Discord message colour", "Text of [Discord] messages in your chat", textChoices,
                        () -> cfg.discordTextColor, (c) -> { cfg.discordTextColor = c; BridgeConfig.save(); }),
                new ColorRow("Bridge chat colour", "Text of [Bridge] (/bc) messages", textChoices,
                        () -> cfg.bridgeTextColor, (c) -> { cfg.bridgeTextColor = c; BridgeConfig.save(); }),
                new ColorRow("Bot reply colour", "Text of [Bot] answers to !nw and the rest", textChoices,
                        () -> cfg.botTextColor, (c) -> { cfg.botTextColor = c; BridgeConfig.save(); }));
    }

    /** Where each swatch of a row goes: {x, y} per choice, wrapping when the panel is narrow. */
    private int[][] swatchSpots(ColorRow row, int y) {
        int[][] spots = new int[row.choices().size()][];
        int x = px + 14;
        int sy = y + 30;
        for (int i = 0; i < spots.length; i++) {
            if (x + SWATCH > px + pw - 14) {
                x = px + 14;
                sy += SWATCH + SWATCH_GAP;
            }
            spots[i] = new int[]{x, sy};
            x += SWATCH + SWATCH_GAP;
        }
        return spots;
    }

    private int rowHeight(ColorRow row, int y) {
        int[][] spots = swatchSpots(row, y);
        return spots[spots.length - 1][1] + SWATCH - y + 10;
    }

    /** Every colour row, one under another; returns their total height. */
    private int drawColors(GuiGraphicsExtractor g, int y, int mouseX, int mouseY) {
        int top = y;
        for (ColorRow row : colorRows()) {
            g.text(font, row.name(), px + 14, y + 5, TEXT, false);
            g.text(font, ellipsize(row.description(), pw - 28), px + 14, y + 16, MUTED, false);
            String chosen = row.get().get();
            int[][] spots = swatchSpots(row, y);
            for (int i = 0; i < spots.length; i++) {
                String color = row.choices().get(i);
                int x = spots[i][0], sy = spots[i][1];
                boolean hover = inContent(mouseY) && inside(mouseX, mouseY, x - 2, sy - 2, SWATCH + 4, SWATCH + 4);
                if (color.equals(chosen)) roundRect(g, x - 2, sy - 2, SWATCH + 4, SWATCH + 4, (SWATCH + 4) / 2, 0xFFFFFFFF);
                Integer rgb = NameColors.rgb(color);
                int grow = hover ? 1 : 0;
                int size = SWATCH + grow * 2;
                if (rgb == null) {
                    // "none": a grey disc with a slash through it
                    roundRect(g, x - grow, sy - grow, size, size, size / 2, 0xFF3A3E49);
                    for (int k = 2; k < SWATCH - 2; k++) g.fill(x + k, sy + SWATCH - 1 - k, x + k + 1, sy + SWATCH - k, MUTED);
                } else {
                    roundRect(g, x - grow, sy - grow, size, size, size / 2, 0xFF000000 | rgb);
                }
            }
            y += rowHeight(row, y);
        }
        return y - top;
    }

    /** Clicks on a swatch in any colour row: true if one was picked. */
    private boolean clickColor(double mx, double my, int y) {
        for (ColorRow row : colorRows()) {
            int[][] spots = swatchSpots(row, y);
            for (int i = 0; i < spots.length; i++) {
                if (inside(mx, my, spots[i][0] - 2, spots[i][1] - 2, SWATCH + 4, SWATCH + 4)) {
                    row.set().accept(row.choices().get(i));
                    return true;
                }
            }
            y += rowHeight(row, y);
        }
        return false;
    }

    private int drawOnline(GuiGraphicsExtractor g, int top, BridgeConnection connection) {
        int y = top;
        if (!connection.isReady()) {
            g.text(font, "Join Hypixel to see who's online.", px + 14, y + 4, MUTED, false);
            return 20;
        }
        List<String> names = connection.onlinePlayers();
        g.text(font, ellipsize(names.size() + " online with the mod in " + connection.guildName(), pw - 28), px + 14, y + 2, MUTED, false);
        y += 18;
        if (names.isEmpty()) {
            g.text(font, "Nobody yet.", px + 14, y, FAINT, false);
            return y - top + 14;
        }
        int colW = (pw - 28) / 2;
        for (int i = 0; i < names.size(); i++) {
            int x = px + 14 + (i % 2) * colW;
            int ry = y + (i / 2) * 14;
            roundRect(g, x, ry + 2, 5, 5, 2, GREEN);
            g.text(font, names.get(i), x + 10, ry, TEXT, false);
        }
        return 18 + ((names.size() + 1) / 2) * 14 + 6;
    }

    private static final String[][] COMMANDS = {
            {"#In game"},
            {"/gbridge", "this screen"},
            {"/bc <message>", "talk to Discord and mod users"},
            {"/gbridge online", "who has the mod"},
            {"/gbridge tasks", "staff tasks waiting for you"},
            {"/gbridge colour <colour>", "your name colour"},
            {"/gbridge textcolour", "discord|bridge|bot <colour>"},
            {"/gbridge reconnect", "sign in again"},
            {"#Guild chat, /bc or Discord"},
            {"!nw [player]", "networth"},
            {"!lvl [player]", "SkyBlock level"},
            {"!skills [player]", "skill average"},
            {"!slayers [player]", "slayer levels"},
            {"!cata [player]", "Catacombs level"},
            {"!weight [player]", "Senither, Lily and farming"},
            {"!guild [player]", "guild averages"},
            {"!vs <a> <b>", "compare two players"},
            {"!online", "who has the mod"},
            {"#Guild Master"},
            {"/gbridge claim <code>", "link the guild to Discord"},
    };

    private int drawCommands(GuiGraphicsExtractor g, int top) {
        int y = top;
        // Descriptions start after the longest command (but leave them at least a third of the panel).
        int longest = 0;
        for (String[] line : COMMANDS) if (!line[0].startsWith("#")) longest = Math.max(longest, font.width(line[0]));
        int descX = px + 14 + Math.min(longest + 12, pw * 2 / 3 - 14);
        for (String[] line : COMMANDS) {
            if (line[0].startsWith("#")) {
                if (y != top) y += 6;
                g.text(font, line[0].substring(1).toUpperCase(), px + 14, y, ACCENT, false);
                y += 13;
                continue;
            }
            g.text(font, line[0], px + 14, y, TEXT, false);
            g.text(font, ellipsize(line[1], px + pw - 14 - descX), descX, y, MUTED, false);
            y += 12;
        }
        return y - top + 4;
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int contentHeight) {
        int view = contentBottom - contentTop;
        if (contentHeight <= view) return;
        int barH = Math.max(16, view * view / contentHeight);
        int barY = contentTop + (int) ((view - barH) * (scroll / (contentHeight - view)));
        roundRect(g, px + pw - 6, barY + 2, 3, barH - 4, 1, 0x40FFFFFF);
    }

    private void pill(GuiGraphicsExtractor g, String label, int x, int y, int w, boolean primary, boolean hover) {
        int bg = primary ? (hover ? 0xFF7DBDFF : ACCENT) : (hover ? 0xFF353945 : 0xFF2A2D36);
        roundRect(g, x, y, w, 18, 6, bg);
        g.centeredText(font, label, x + w / 2, y + 5, primary ? 0xFF0B1220 : TEXT);
    }

    // ── Input ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            lastEventClick = System.currentTimeMillis();
            eventSinceRelease = true;
            if (click(event.x(), event.y())) return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * Some clients (Lunar) don't pass mouse clicks to mod screens. So the
     * left button is also checked every frame, and a fresh press that no
     * click event arrived for is handled here instead.
     */
    private void pollClick(int mouseX, int mouseY) {
        Boolean held = MouseButtons.leftDown();
        if (held == null) return; // can't read it here: normal clicks only
        boolean down = held;
        Minecraft minecraft = Minecraft.getInstance();
        // A press the normal click event already handled is skipped: either an event
        // arrived since the button was last released, or one arrived very recently.
        if (down && !buttonWasDown && !eventSinceRelease && System.currentTimeMillis() - lastEventClick > 250) {
            if (!loggedFallback) {
                loggedFallback = true;
                GuildBridgeMod.LOG.info("Settings screen: a click arrived without a click event (client is intercepting them); handling it directly");
            }
            minecraft.execute(() -> click(mouseX, mouseY)); // after this frame, not while drawing it
        }
        if (!down) eventSinceRelease = false;
        buttonWasDown = down;
    }

    /** A left click at (mx, my): true if it hit something. */
    private boolean click(double mx, double my) {
        layout();

        // Tabs
        int tx = px + 14;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]);
            if (inside(mx, my, tx - 4, py + 42, w + 8, 18)) {
                if (tab != i) {
                    tab = i;
                    scroll = 0;
                    lastOnlineRequest = 0;
                }
                return true;
            }
            tx += w + 18;
        }
        // Footer
        if (inside(mx, my, doneX(), footerY(), 52, 18)) {
            onClose();
            return true;
        }
        if (inside(mx, my, reconnectX(), footerY(), 70, 18)) {
            GuildBridgeMod.connection().reconnect();
            return true;
        }
        // Toggle rows
        if (tab == 0 && inContent((int) my)) {
            int y = contentTop + 6 - (int) scroll;
            for (Toggle t : toggles) {
                if (inside(mx, my, px + 6, y, pw - 12, ROW_H - 2) && t.usable().getAsBoolean()) {
                    t.set().accept(!t.get().getAsBoolean());
                    BridgeConfig.save();
                    GuildBridgeMod.sendPrefs(); // e.g. "Discord messages" shows next to your name in Discord
                    return true;
                }
                y += ROW_H;
            }
            if (clickColor(mx, my, y)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= vertical * 14;
        return true; // clamped on the next frame
    }

    @Override
    public void onClose() {
        ScreenAccess.setScreen(Minecraft.getInstance(), null); // back to the game
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void layout() {
        pw = Math.min(360, width - 16);
        ph = Math.min(290, height - 16);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        contentTop = py + 64;
        contentBottom = py + ph - 30;
    }

    private int footerY() {
        return py + ph - 24;
    }

    private int doneX() {
        return px + pw - 14 - 52;
    }

    private int reconnectX() {
        return doneX() - 8 - 70;
    }

    private boolean inContent(int my) {
        return my >= contentTop && my < contentBottom;
    }

    private void clampScroll(int contentHeight) {
        int max = Math.max(0, contentHeight - (contentBottom - contentTop));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "...") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    /** A filled rectangle with rounded corners (radius r), drawn line by line. */
    private static void roundRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - i - 1, x + w - inset, y + h - i, color);
        }
    }
}
