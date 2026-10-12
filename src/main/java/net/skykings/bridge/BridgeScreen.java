package net.skykings.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
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
    private String editingHex;            // the colour row whose hex code is being typed (patrons), or null
    private String hexBuffer = "";
    private String hexNote;               // a short note under the colour rows (e.g. "join Hypixel first")
    // The colour picker (patrons): hue, saturation and brightness, 0..1; what's being dragged.
    private float pickH, pickS = 1f, pickV = 1f;
    private int dragging;                 // 0 nothing, 1 the colour square, 2 the hue strip
    private boolean mouseDown;            // from click/release events (when the button can't be read directly)
    private int pickerX = -1, pickerY = -1; // where the picker was drawn this frame
    private static final int PICK_W = 132, PICK_H = 66, HUE_H = 8, PICKER_ROW_H = 96;

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
        followDrag(mouseX, mouseY);

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
                new ColorRow("Bridge chat colour", "Text of [Bridge Chat] (/bc) messages", textChoices,
                        () -> cfg.bridgeTextColor, (c) -> { cfg.bridgeTextColor = c; BridgeConfig.save(); }),
                new ColorRow("Bot reply colour", "Text of [Bot] answers to !nw and the rest", textChoices,
                        () -> cfg.botTextColor, (c) -> { cfg.botTextColor = c; BridgeConfig.save(); }));
    }

    private static final String CUSTOM = "#"; // the last swatch of every row: any hex colour (patrons)

    /** Where each swatch of a row goes: {x, y} per choice (and the "#" one last), wrapping when the panel is narrow. */
    private int[][] swatchSpots(ColorRow row, int y) {
        int[][] spots = new int[row.choices().size() + 1][];
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
        return spots[spots.length - 1][1] + SWATCH - y + 10 + (row.name().equals(editingHex) ? PICKER_ROW_H : 0);
    }

    private String choiceAt(ColorRow row, int i) {
        return i < row.choices().size() ? row.choices().get(i) : CUSTOM;
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
                String color = choiceAt(row, i);
                int x = spots[i][0], sy = spots[i][1];
                boolean hover = inContent(mouseY) && inside(mouseX, mouseY, x - 2, sy - 2, SWATCH + 4, SWATCH + 4);
                boolean custom = color.equals(CUSTOM);
                boolean selected = custom ? NameColors.isHex(chosen) : color.equals(chosen);
                if (selected) roundRect(g, x - 2, sy - 2, SWATCH + 4, SWATCH + 4, (SWATCH + 4) / 2, 0xFFFFFFFF);
                Integer rgb = custom ? null : NameColors.rgb(color);
                int grow = hover ? 1 : 0;
                int size = SWATCH + grow * 2;
                if (custom) {
                    // "#": their custom colour, or a rainbow ring for "pick any colour"
                    if (NameColors.isHex(chosen)) {
                        roundRect(g, x - grow, sy - grow, size, size, size / 2, 0xFF000000 | NameColors.rgb(chosen));
                    } else {
                        int[] rainbow = {0xFFFF5555, 0xFFFFAA00, 0xFFFFFF55, 0xFF55FF55, 0xFF55FFFF, 0xFF5555FF, 0xFFFF55FF};
                        for (int k = 0; k < size; k++) g.fill(x - grow + k, sy - grow + 2, x - grow + k + 1, sy - grow + size - 2, rainbow[k * rainbow.length / size]);
                    }
                    g.centeredText(font, "#", x + SWATCH / 2 + 1, sy + 2, 0xFFFFFFFF);
                    if (hover) g.setTooltipForNextFrame(font, Component.literal(GuildBridgeMod.connection().isPatron()
                            ? "Any colour (#rrggbb)" : "Any colour: SkyKings patrons"), mouseX, mouseY);
                } else if (rgb == null) {
                    // "none": a grey disc with a slash through it
                    roundRect(g, x - grow, sy - grow, size, size, size / 2, 0xFF3A3E49);
                    for (int k = 2; k < SWATCH - 2; k++) g.fill(x + k, sy + SWATCH - 1 - k, x + k + 1, sy + SWATCH - k, MUTED);
                } else {
                    roundRect(g, x - grow, sy - grow, size, size, size / 2, 0xFF000000 | rgb);
                }
            }
            if (row.name().equals(editingHex)) drawPicker(g, spots[spots.length - 1][1] + SWATCH + 8, mouseX, mouseY);
            y += rowHeight(row, y);
        }
        if (hexNote != null) {
            g.text(font, ellipsize(hexNote, pw - 28), px + 14, y, AMBER, false);
            y += 14;
        }
        return y - top;
    }

    /**
     * The colour picker: a saturation/brightness square and a hue strip you drag across,
     * a preview, the hex code (it can also be typed), and Save / Cancel.
     */
    private void drawPicker(GuiGraphicsExtractor g, int y, int mouseX, int mouseY) {
        int x = px + 14;
        pickerX = x;
        pickerY = y;
        roundRect(g, x - 4, y - 4, pw - 20, PICKER_ROW_H - 4, 6, 0xFF0B0C10);
        // Saturation (left to right) and brightness (top to bottom) for the current hue, in 3px cells.
        int cell = 3;
        for (int cx = 0; cx < PICK_W; cx += cell) {
            for (int cy = 0; cy < PICK_H; cy += cell) {
                int rgb = hsv(pickH, cx / (float) (PICK_W - cell), 1f - cy / (float) (PICK_H - cell));
                g.fill(x + cx, y + cy, x + Math.min(PICK_W, cx + cell), y + Math.min(PICK_H, cy + cell), 0xFF000000 | rgb);
            }
        }
        int mx = x + Math.round(pickS * (PICK_W - 1)), my = y + Math.round((1f - pickV) * (PICK_H - 1));
        g.fill(mx - 3, my - 3, mx + 4, my - 2, 0xFFFFFFFF);
        g.fill(mx - 3, my + 3, mx + 4, my + 4, 0xFFFFFFFF);
        g.fill(mx - 3, my - 3, mx - 2, my + 4, 0xFFFFFFFF);
        g.fill(mx + 3, my - 3, mx + 4, my + 4, 0xFFFFFFFF);
        // Hue strip
        int hy = y + PICK_H + 6;
        for (int i = 0; i < PICK_W; i++) g.fill(x + i, hy, x + i + 1, hy + HUE_H, 0xFF000000 | hsv(i / (float) (PICK_W - 1), 1f, 1f));
        int hx = x + Math.round(pickH * (PICK_W - 1));
        g.fill(hx - 1, hy - 2, hx + 2, hy + HUE_H + 2, 0xFFFFFFFF);
        // Preview, hex code and buttons
        int rx = x + PICK_W + 12;
        int rgb = hsv(pickH, pickS, pickV);
        roundRect(g, rx, y, 28, 28, 6, 0xFF000000 | rgb);
        g.text(font, Component.literal("Sample text").withStyle((st) -> st.withColor(rgb)), rx + 36, y + 4, TEXT, false);
        g.text(font, "Drag to pick a colour", rx + 36, y + 16, MUTED, false);
        roundRect(g, rx, y + 36, 70, 16, 4, 0xFF1C1E26);
        boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
        g.text(font, "#" + hexBuffer + (caret && hexBuffer.length() < 6 ? "_" : ""), rx + 5, y + 40, TEXT, false);
        g.text(font, "or type it", rx + 76, y + 40, FAINT, false);
        pill(g, "Save", rx, y + 58, 50, true, inside(mouseX, mouseY, rx, y + 58, 50, 18));
        pill(g, "Cancel", rx + 56, y + 58, 56, false, inside(mouseX, mouseY, rx + 56, y + 58, 56, 18));
    }

    /** HSV (0..1) to RGB. (Not java.awt.Color: the game's Java may not include AWT.) */
    private static int hsv(float h, float s, float v) {
        s = Math.max(0f, Math.min(1f, s));
        v = Math.max(0f, Math.min(1f, v));
        float hh = (h - (float) Math.floor(h)) * 6f;
        int sector = (int) hh;
        float f = hh - sector, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (sector) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /** RGB to {hue, saturation, brightness}, each 0..1. */
    private static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f;
            if (h < 0) h += 1f;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }

    private void setPickFromHex(String hex) {
        Integer rgb = NameColors.rgb("#" + hex);
        if (rgb == null) return;
        float[] hsb = toHsv(rgb);
        if (hsb[1] > 0.001f) pickH = hsb[0]; // greys keep the hue the strip was on
        pickS = hsb[1];
        pickV = hsb[2];
    }

    private void syncHexFromPick() {
        hexBuffer = String.format(java.util.Locale.ROOT, "%06X", hsv(pickH, pickS, pickV));
    }

    /** A press on the picker: start dragging the square or the strip, or press Save / Cancel. */
    private boolean clickPicker(double mx, double my) {
        if (editingHex == null || pickerX < 0) return false;
        int x = pickerX, y = pickerY, hy = y + PICK_H + 6, rx = x + PICK_W + 12;
        if (inside(mx, my, x - 2, y - 2, PICK_W + 4, PICK_H + 4)) { dragging = 1; dragTo(mx, my); return true; }
        if (inside(mx, my, x - 2, hy - 3, PICK_W + 4, HUE_H + 6)) { dragging = 2; dragTo(mx, my); return true; }
        if (inside(mx, my, rx, y + 58, 50, 18)) { saveHex(); return true; }
        if (inside(mx, my, rx + 56, y + 58, 56, 18)) { editingHex = null; return true; }
        return inside(mx, my, x - 4, y - 4, pw - 20, PICKER_ROW_H - 4); // other clicks inside the picker do nothing
    }

    private void dragTo(double mx, double my) {
        if (dragging == 1) {
            pickS = (float) Math.max(0, Math.min(1, (mx - pickerX) / (PICK_W - 1)));
            pickV = 1f - (float) Math.max(0, Math.min(1, (my - pickerY) / (PICK_H - 1)));
        } else if (dragging == 2) {
            pickH = (float) Math.max(0, Math.min(1, (mx - pickerX) / (PICK_W - 1)));
        }
        syncHexFromPick();
    }

    /** Every frame: keep following the mouse while the button is held after pressing on the picker. */
    private void followDrag(int mouseX, int mouseY) {
        if (dragging == 0) return;
        Boolean polled = MouseButtons.leftDown();
        boolean held = polled != null ? polled : mouseDown;
        if (!held || editingHex == null) {
            dragging = 0;
            return;
        }
        dragTo(mouseX, mouseY);
    }

    /** The "#" swatch: patrons type a hex code; everyone else is shown the premium page. */
    private void clickCustom(ColorRow row) {
        BridgeConnection connection = GuildBridgeMod.connection();
        hexNote = null;
        if (!connection.isReady()) {
            hexNote = "Join Hypixel first: custom colours are checked when the bridge connects.";
            return;
        }
        if (!connection.isPatron()) {
            hexNote = "Custom colours are for SkyKings patrons: skykings.net/premium";
            ConfirmLinkScreen.confirmLinkNow(this, java.net.URI.create(ChatDisplay.PREMIUM_URL));
            return;
        }
        editingHex = row.name();
        Integer rgb = NameColors.rgb(row.get().get());
        hexBuffer = String.format(java.util.Locale.ROOT, "%06X", rgb == null ? 0xFF5555 : rgb); // start from their current colour
        setPickFromHex(hexBuffer);
    }

    private void saveHex() {
        if (hexBuffer.length() != 6) return;
        for (ColorRow row : colorRows()) {
            if (row.name().equals(editingHex)) row.set().accept("#" + hexBuffer.toLowerCase());
        }
        editingHex = null;
    }

    // Key codes differ between versions (26.3 uses SDL), so they're read from the game, not written in.
    private static int key(String name) {
        try {
            return com.mojang.blaze3d.platform.InputConstants.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (editingHex != null) {
            int k = event.key();
            if (k == key("KEY_ESCAPE")) editingHex = null;
            else if (k == key("KEY_RETURN") || k == key("KEY_NUMPADENTER")) saveHex();
            else if (k == key("KEY_BACKSPACE") && !hexBuffer.isEmpty()) hexBuffer = hexBuffer.substring(0, hexBuffer.length() - 1);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (editingHex == null) return super.charTyped(event);
        String typed = event.codepointAsString().replace("#", "");
        for (char c : typed.toCharArray()) {
            if (hexBuffer.length() < 6 && Character.digit(c, 16) >= 0) hexBuffer += Character.toUpperCase(c);
        }
        if (hexBuffer.length() == 6) setPickFromHex(hexBuffer);
        return true;
    }

    /** Clicks on a swatch in any colour row: true if one was picked. */
    private boolean clickColor(double mx, double my, int y) {
        for (ColorRow row : colorRows()) {
            int[][] spots = swatchSpots(row, y);
            for (int i = 0; i < spots.length; i++) {
                if (inside(mx, my, spots[i][0] - 2, spots[i][1] - 2, SWATCH + 4, SWATCH + 4)) {
                    String choice = choiceAt(row, i);
                    if (choice.equals(CUSTOM)) {
                        clickCustom(row);
                    } else {
                        editingHex = null;
                        hexNote = null;
                        row.set().accept(choice);
                    }
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
            {"/gpv [player] [profile]", "profile stats, like the website"},
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
            mouseDown = true;
            // Only a click event that hit something stops the polled click from also running:
            // some clients send click events with positions that don't line up with the screen.
            if (click(event.x(), event.y())) {
                lastEventClick = System.currentTimeMillis();
                eventSinceRelease = true;
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            mouseDown = false;
            dragging = 0;
        }
        return super.mouseReleased(event);
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
        if (tab == 0 && inContent((int) my) && clickPicker(mx, my)) return true;

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
