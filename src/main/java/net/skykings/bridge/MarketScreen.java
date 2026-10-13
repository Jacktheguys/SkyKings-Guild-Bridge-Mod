package net.skykings.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /gscanner, /gbazaar, /gmarket: the skykings.net market tools in-game. One table per
 * tool (auction snipes, bazaar flips, forge, Kat, NPC and book flips), filled by the
 * bridge. Clicking a row opens that auction (/viewauction) or searches the Bazaar (/bz):
 * one click, one command, built and checked by GuildBridgeMod.runMarketAction.
 */
public final class MarketScreen extends Screen {
    static final String[] TOOLS = {"snipes", "bazaar", "forge", "kat", "npc", "books"};
    private static final String[] TOOL_TITLES = {"Auction Snipes", "Bazaar Flips", "Forge", "Kat", "NPC", "Books"};
    private static final String[] TOOL_ICONS = {"gold_ingot", "emerald", "anvil", "bone", "villager_spawn_egg", "enchanted_book"};

    private static final int PAGE = 0xF20D0F14;
    private static final int PAGE_EDGE = 0xFF262A33;
    private static final int CARD = 0xFF15171D;
    private static final int CARD_EDGE = 0xFF252932;
    private static final int ROW_HOVER = 0xFF1E222B;
    private static final int TILE = 0xFF1B1E26;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9AA0AB;
    private static final int FAINT = 0xFF5C626D;
    private static final int ORANGE = 0xFFF5A623;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFF2545B;
    private static final int ROW_H = 22, SLOT = 18;
    private static final Map<String, Integer> RARITY = Map.ofEntries(
            Map.entry("COMMON", 0xFFFFFF), Map.entry("UNCOMMON", 0x55FF55), Map.entry("RARE", 0x5555FF),
            Map.entry("EPIC", 0xAA00AA), Map.entry("LEGENDARY", 0xFFAA00), Map.entry("MYTHIC", 0xFF55FF),
            Map.entry("DIVINE", 0x55FFFF), Map.entry("SPECIAL", 0xFF5555), Map.entry("VERY_SPECIAL", 0xFF5555),
            Map.entry("ULTIMATE", 0xAA0000), Map.entry("ADMIN", 0xAA0000));

    private int tool;
    private volatile JsonObject data;
    private volatile String error;
    private volatile boolean loading;
    private String search = "";
    private double scroll;
    private int contentHeight;
    private long refreshedAt;

    private boolean buttonWasDown = true;
    private long lastEventClick;

    private int px, py, pw, ph, top, bottom;
    private final List<int[]> tabSpots = new ArrayList<>();
    private final List<Object[]> rowSpots = new ArrayList<>(); // {int[]{x, y, w, h}, JsonObject row}

    private MarketScreen(int tool) {
        super(Component.literal("SkyKings Market"));
        this.tool = tool;
    }

    public static MarketScreen load(String toolName) {
        int index = 0;
        for (int i = 0; i < TOOLS.length; i++) if (TOOLS[i].equalsIgnoreCase(toolName)) index = i;
        MarketScreen screen = new MarketScreen(index);
        screen.fetch();
        return screen;
    }

    private void fetch() {
        loading = true;
        error = null;
        refreshedAt = System.currentTimeMillis();
        boolean asked = GuildBridgeMod.connection().requestMarket(TOOLS[tool], (msg) -> {
            loading = false;
            if (msg.has("ok") && msg.get("ok").getAsBoolean() && msg.get("data") instanceof JsonObject d) {
                data = d;
                scroll = 0;
            } else {
                error = msg.has("error") ? ChatDisplay.safe(msg.get("error").getAsString(), 120) : "Couldn't load that right now.";
            }
        });
        if (!asked) {
            loading = false;
            error = "The market tools work while you're connected to the bridge (on Hypixel).";
        }
    }

    @Override
    protected void init() {
        super.init();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.mouseHandler.isMouseGrabbed()) minecraft.mouseHandler.releaseMouse();
    }

    // ── Data helpers ────────────────────────────────────────────────────

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o != null && o.get(key) instanceof JsonArray arr) arr.forEach((e) -> out.add(e.isJsonPrimitive() ? e.getAsString() : ""));
        return out;
    }

    private List<JsonObject> rows() {
        List<JsonObject> out = new ArrayList<>();
        JsonObject d = data;
        if (d == null || !(d.get("rows") instanceof JsonArray arr)) return out;
        String q = search.toLowerCase(Locale.ROOT);
        for (JsonElement e : arr) {
            if (!(e instanceof JsonObject row)) continue;
            String name = LegacyText.strip(str(row, "name"));
            if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) out.add(row);
        }
        return out;
    }

    private static int rarityColor(String rarity) {
        Integer rgb = rarity == null ? null : RARITY.get(rarity.toUpperCase(Locale.ROOT));
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    private void layout() {
        pw = Math.min(width - 12, 640);
        ph = height - 12;
        px = (width - pw) / 2;
        py = 6;
        top = py + 72;
        bottom = py + ph - 4;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int closeX() { return px + pw - 8 - 40; }
    private int refreshX() { return closeX() - 4 - 52; }

    private void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, CARD_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    private void small(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "...") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mouseX, int mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, w, 14);
        g.fill(x, y, x + w, y + 14, hover ? 0xFF3A3E49 : 0xFF23262E);
        g.centeredText(font, label, x + w / 2, y + 3, TEXT);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout();
        pollClick(mouseX, mouseY);

        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PAGE_EDGE);
        g.fill(px, py, px + pw, py + ph, PAGE);
        Component brand = Component.literal("SkyKings").withStyle((s) -> s.withBold(true));
        g.text(font, brand, px + 10, py + 8, ORANGE, false);
        g.text(font, "Market", px + 10 + font.width(brand) + 6, py + 8, MUTED, false);
        button(g, refreshX(), py + 5, 52, loading ? "..." : "Refresh", mouseX, mouseY);
        button(g, closeX(), py + 5, 40, "Close", mouseX, mouseY);

        // Tool tabs (orange pill = selected)
        tabSpots.clear();
        int tx = px + 8;
        for (int i = 0; i < TOOLS.length; i++) {
            int bw = font.width(TOOL_TITLES[i]) + 24;
            boolean selected = i == tool, hover = inside(mouseX, mouseY, tx, py + 24, bw, 16);
            if (selected || hover) g.fill(tx, py + 24, tx + bw, py + 40, selected ? ORANGE : 0xFF262A33);
            MediaStore.Loaded icon = MediaStore.icon("/images/minecraft/minecraft_" + TOOL_ICONS[i] + ".png", null);
            if (icon != null && icon.texture != null) ImageScreen.blit(g, icon.texture, tx + 5, py + 28, 8, 8, icon.width, icon.height);
            g.text(font, TOOL_TITLES[i], tx + 17, py + 28, selected ? 0xFF1A1A1A : TEXT, false);
            tabSpots.add(new int[]{tx, py + 24, bw, 16, i});
            tx += bw + 4;
        }

        // Search box + the tool's note
        JsonObject d = data;
        int sy = py + 46;
        g.fill(px + 8, sy, px + 168, sy + 16, TILE);
        boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
        g.text(font, search.isEmpty() ? "Type to search..." : search + (caret ? "_" : ""), px + 13, sy + 4, search.isEmpty() ? FAINT : TEXT, false);
        String note = d == null ? "" : String.valueOf(str(d, "note") == null ? "" : str(d, "note"));
        small(g, ellipsize(ChatDisplay.safe(note, 300), (int) ((pw - 190) / 0.75f)), px + 176, sy + 5, MUTED);
        g.fill(px + 1, top - 4, px + pw - 1, top - 3, PAGE_EDGE);

        if (d == null || loading) {
            String text = error != null ? error : "Loading...";
            g.centeredText(font, text, px + pw / 2, (top + bottom) / 2 - 4, error != null ? RED : MUTED);
            return;
        }
        if (!TOOLS[tool].equals(str(d, "tool"))) return; // an older answer for another tab

        g.enableScissor(px + 1, top, px + pw - 1, bottom);
        List<Component> hover = null;
        try {
            hover = drawTable(g, d, mouseX, mouseY);
        } catch (RuntimeException e) {
            error = "Couldn't show this list.";
            GuildBridgeMod.LOG.warn("Couldn't draw the market list", e);
        } finally {
            g.disableScissor();
        }
        clampScroll();
        if (error != null) g.centeredText(font, error, px + pw / 2, bottom - 12, RED);
        if (hover != null) g.setComponentTooltipForNextFrame(font, hover, mouseX, mouseY);
    }

    /** Header row, then one row per item: picture, name, the tool's columns. Returns the hovered row's tooltip. */
    private List<Component> drawTable(GuiGraphicsExtractor g, JsonObject d, int mouseX, int mouseY) {
        rowSpots.clear();
        List<String> columns = strings(d, "columns");
        List<JsonObject> rows = rows();
        int x = px + 8, w = pw - 16;
        int colW = Math.min(78, (w - 180) / Math.max(1, columns.size()));
        int nameW = w - colW * columns.size() - SLOT - 14;
        int y = top + 2 - (int) scroll;
        boolean inContent = mouseY >= top && mouseY < bottom;

        // Header
        for (int c = 0; c < columns.size(); c++) {
            String label = columns.get(c).toUpperCase(Locale.ROOT);
            int right = x + w - 6 - (columns.size() - 1 - c) * colW;
            small(g, label, right - (int) Math.ceil(font.width(label) * 0.75f), y + 2, FAINT);
        }
        small(g, "ITEM", x + SLOT + 10, y + 2, FAINT);
        y += 12;

        if (rows.isEmpty()) {
            card(g, x, y, w, 22, CARD);
            g.text(font, search.isEmpty() ? "Nothing right now." : "Nothing matches \"" + search + "\".", x + 8, y + 7, FAINT, false);
            contentHeight = y + 22 + (int) scroll - top;
            return null;
        }
        List<Component> hover = null;
        for (JsonObject row : rows) {
            boolean over = inContent && inside(mouseX, mouseY, x, y, w, ROW_H - 2);
            boolean clickable = row.get("action") instanceof JsonObject;
            card(g, x, y, w, ROW_H - 2, over && clickable ? ROW_HOVER : CARD);
            // Picture
            int rgb = rarityColor(str(row, "rarity"));
            g.fill(x + 3, y + 1, x + 3 + SLOT, y + 1 + SLOT, rgb);
            g.fill(x + 4, y + 2, x + 2 + SLOT, y + SLOT, 0xFF111318);
            String image = str(row, "image");
            MediaStore.Loaded icon = image == null ? null : MediaStore.icon(image, null);
            if (icon != null && icon.texture != null) ImageScreen.blit(g, icon.texture, x + 4, y + 2, 16, 16, icon.width, icon.height);
            // Name and columns
            g.text(font, ellipsize(LegacyText.strip(str(row, "name")), nameW), x + SLOT + 10, y + 6, rgb, false);
            List<String> cells = strings(row, "cells");
            for (int c = 0; c < cells.size() && c < columns.size(); c++) {
                String cell = ChatDisplay.safe(cells.get(c), 20);
                int right = x + w - 6 - (columns.size() - 1 - c) * colW;
                int color = cell.startsWith("+") ? GREEN : cell.startsWith("-") && cell.length() > 1 ? RED : TEXT;
                g.text(font, cell, right - font.width(cell), y + 6, color, false);
            }
            rowSpots.add(new Object[]{new int[]{x, y, w, ROW_H - 2}, row});
            if (over) {
                List<Component> lines = new ArrayList<>();
                lines.add(LegacyText.parse(str(row, "name"), ChatFormatting.WHITE).copy().withStyle((s) -> s.withColor(rgb & 0xFFFFFF)));
                for (String l : strings(row, "lore")) lines.add(LegacyText.parse(l, ChatFormatting.GRAY));
                hover = lines;
            }
            y += ROW_H;
        }
        long age = (System.currentTimeMillis() - refreshedAt) / 1000;
        small(g, rows.size() + " shown · loaded " + (age < 60 ? age + "s" : age / 60 + "m") + " ago · /gmarket " + TOOLS[tool], x + 2, y + 4, FAINT);
        contentHeight = y + 16 + (int) scroll - top;
        return hover;
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ── Input ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Only a click event that hit something stops the polled click below from also running.
        if (event.button() == 0 && click(event.x(), event.y())) {
            lastEventClick = System.currentTimeMillis();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Clients like Lunar don't pass clicks to mod screens: a fresh press of the button also counts. */
    private void pollClick(int mouseX, int mouseY) {
        Boolean held = MouseButtons.leftDown();
        if (held == null) return;
        if (held && !buttonWasDown && System.currentTimeMillis() - lastEventClick > 250) {
            Minecraft.getInstance().execute(() -> click(mouseX, mouseY));
        }
        buttonWasDown = held;
    }

    private boolean click(double mx, double my) {
        layout();
        if (inside(mx, my, closeX(), py + 5, 40, 14)) {
            onClose();
            return true;
        }
        if (inside(mx, my, refreshX(), py + 5, 52, 14)) {
            if (!loading) fetch();
            return true;
        }
        for (int[] t : tabSpots) {
            if (inside(mx, my, t[0], t[1], t[2], t[3])) {
                if (tool != t[4]) {
                    tool = t[4];
                    data = null;
                    search = "";
                    fetch();
                }
                return true;
            }
        }
        if (my < top || my >= bottom) return false;
        for (Object[] spot : rowSpots) {
            int[] r = (int[]) spot[0];
            if (!inside(mx, my, r[0], r[1], r[2], r[3])) continue;
            JsonObject action = ((JsonObject) spot[1]).get("action") instanceof JsonObject a ? a : null;
            if (action == null) return true;
            String type = str(action, "type");
            String value = "auction".equals(type) ? str(action, "id") : str(action, "query");
            if (GuildBridgeMod.runMarketAction(type, value)) onClose(); // Hypixel opens the auction / Bazaar
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= vertical * 18;
        return true;
    }

    // Typing filters the list (Backspace deletes, Escape closes as usual).
    private static int key(String name) {
        try {
            return com.mojang.blaze3d.platform.InputConstants.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == key("KEY_BACKSPACE") && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            scroll = 0;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        String typed = event.codepointAsString();
        if (search.length() < 30 && typed.matches("[A-Za-z0-9 '+:-]")) {
            search += typed;
            scroll = 0;
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        ScreenAccess.setScreen(Minecraft.getInstance(), null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
