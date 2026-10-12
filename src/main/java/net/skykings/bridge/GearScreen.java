package net.skykings.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /gpv <player> [profile]: the Gear tab of skykings.net/profilestats, in-game.
 * Armor, equipment, the active pet, weapons and tools, with each item's full
 * tooltip on hover. The data and item pictures come from the SkyKings website.
 */
public final class GearScreen extends Screen {
    private static final int PANEL = 0xF0121318;
    private static final int PANEL_EDGE = 0xFF2A2D37;
    private static final int CARD = 0xFF181A21;
    private static final int SLOT = 0xFF0E0F13;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9097A3;
    private static final int FAINT = 0xFF5C626D;
    private static final int ACCENT = 0xFF5AA9FF;
    private static final int RED = 0xFFF2545B;
    private static final int SLOT_SIZE = 20;
    private static final int ROW = 22;

    private static final Map<String, Integer> RARITY = Map.ofEntries(
            Map.entry("COMMON", 0xFFFFFF), Map.entry("UNCOMMON", 0x55FF55), Map.entry("RARE", 0x5555FF),
            Map.entry("EPIC", 0xAA00AA), Map.entry("LEGENDARY", 0xFFAA00), Map.entry("MYTHIC", 0xFF55FF),
            Map.entry("DIVINE", 0x55FFFF), Map.entry("SPECIAL", 0xFF5555), Map.entry("VERY_SPECIAL", 0xFF5555),
            Map.entry("ULTIMATE", 0xAA0000), Map.entry("ADMIN", 0xAA0000));

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private record Item(String name, List<String> lore, String rarity, int count, String image, Integer color, String source) {}

    private final String playerName;
    private volatile JsonObject data;
    private volatile String error;
    private double scroll;
    private int contentHeight;

    // Clicks: normal events, plus polling for clients (Lunar) that don't pass clicks to mod screens.
    private boolean buttonWasDown = true;
    private long lastEventClick;

    // Layout, worked out each frame
    private int px, py, pw, ph, contentTop, contentBottom;
    private final List<int[]> tabSpots = new ArrayList<>(); // {x, y, w, index}

    private GearScreen(String playerName) {
        super(Component.literal(playerName + "'s gear"));
        this.playerName = playerName;
    }

    /** Opens the screen (on the next tick, see GuildBridgeMod) and starts loading. */
    public static GearScreen load(String player, String profile) {
        GearScreen screen = new GearScreen(player);
        screen.fetch(profile);
        return screen;
    }

    private void fetch(String profile) {
        data = null;
        error = null;
        scroll = 0;
        String url = MediaStore.SITE + "/api/mod/gear/" + URLEncoder.encode(playerName, StandardCharsets.UTF_8)
                + (profile == null ? "" : "?profile=" + URLEncoder.encode(profile, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SkyKings-Guild-Bridge/" + GuildBridgeMod.version()).GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, failure) -> {
            if (failure != null) {
                error = "Couldn't reach skykings.net.";
                return;
            }
            try {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("success") && json.get("success").getAsBoolean()) data = json;
                else error = json.has("error") ? ChatDisplay.safe(json.get("error").getAsString(), 120) : "Couldn't load that player.";
            } catch (Exception e) {
                error = response.statusCode() == 429 ? "Too many lookups, try again in a minute." : "Couldn't load that player.";
            }
        });
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
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static Item item(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        List<String> lore = new ArrayList<>();
        if (o.get("lore") instanceof JsonArray arr) arr.forEach((l) -> lore.add(l.isJsonNull() ? "" : l.getAsString()));
        Integer color = o.get("color") == null || o.get("color").isJsonNull() ? null : o.get("color").getAsInt();
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        return new Item(str(o, "name"), lore, str(o, "rarity"), count, str(o, "image"), color, str(o, "source"));
    }

    private List<Item> items(String key) {
        List<Item> out = new ArrayList<>();
        JsonObject d = data;
        if (d != null && d.get(key) instanceof JsonArray arr) arr.forEach((e) -> out.add(item(e)));
        return out;
    }

    private static int rarityColor(Item it) {
        Integer rgb = it.rarity() == null ? null : RARITY.get(it.rarity().toUpperCase());
        return 0xFF000000 | (rgb != null ? rgb : LegacyText.firstColor(it.name(), 0xAAAAAA));
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    private void layout() {
        pw = Math.min(width - 20, 470);
        ph = Math.min(height - 20, 320);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        contentTop = py + 50;
        contentBottom = py + ph - 8;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int websiteX() { return px + pw - 14 - 64 - 6 - 46; }
    private int closeX() { return px + pw - 14 - 46; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout();
        pollClick(mouseX, mouseY);

        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PANEL_EDGE);
        g.fill(px, py, px + pw, py + ph, PANEL);
        g.fill(px, py, px + pw, py + 2, ACCENT);

        JsonObject d = data;
        String name = d != null && d.get("player") instanceof JsonObject p ? str(p, "name") : playerName;
        g.text(font, Component.literal(name + "'s gear").withStyle((s) -> s.withBold(true)), px + 14, py + 10, TEXT, false);
        button(g, websiteX(), py + 8, 64, "Website", mouseX, mouseY, d != null);
        button(g, closeX(), py + 8, 46, "Close", mouseX, mouseY, true);

        // Profile tabs
        tabSpots.clear();
        if (d != null && d.get("profiles") instanceof JsonArray profiles) {
            int tx = px + 14;
            for (int i = 0; i < profiles.size(); i++) {
                JsonObject p = profiles.get(i).getAsJsonObject();
                String label = ChatDisplay.safe(str(p, "name"), 20);
                boolean selected = p.has("selected") && p.get("selected").getAsBoolean();
                int w = font.width(label);
                boolean hover = inside(mouseX, mouseY, tx - 4, py + 26, w + 8, 16);
                g.text(font, label, tx, py + 30, selected ? TEXT : hover ? 0xFFC9CDD4 : MUTED, false);
                if (selected) g.fill(tx, py + 41, tx + w, py + 42, ACCENT);
                tabSpots.add(new int[]{tx - 4, py + 26, w + 8, i});
                tx += w + 16;
            }
        }
        g.fill(px + 1, contentTop - 4, px + pw - 1, contentTop - 3, PANEL_EDGE);

        if (d == null) {
            g.centeredText(font, error != null ? error : "Loading " + playerName + "...", px + pw / 2, py + ph / 2, error != null ? RED : MUTED);
            return;
        }
        if (d.has("inventoryApi") && !d.get("inventoryApi").getAsBoolean()) {
            g.centeredText(font, "Inventory API is off for this profile.", px + pw / 2, py + ph / 2, MUTED);
            return;
        }

        g.enableScissor(px + 1, contentTop, px + pw - 1, contentBottom);
        Item hovered = null;
        String hoveredPet = null;
        int y = contentTop + 4 - (int) scroll;
        boolean mouseInContent = mouseY >= contentTop && mouseY < contentBottom;

        // Armor | Equipment | Active pet
        int colW = (pw - 28 - 12) / 3;
        int cardH = 14 + SLOT_SIZE + 10;
        String[] titles = {"Armor", "Equipment", "Active pet"};
        for (int c = 0; c < 3; c++) {
            int cx = px + 14 + c * (colW + 6);
            g.fill(cx, y, cx + colW, y + cardH, CARD);
            g.text(font, titles[c], cx + 6, y + 5, MUTED, false);
            if (c < 2) {
                List<Item> slots = items(c == 0 ? "armor" : "equipment");
                for (int i = 0; i < 4; i++) {
                    int sx = cx + 6 + i * (SLOT_SIZE + 3), sy = y + 16;
                    Item it = i < slots.size() ? slots.get(i) : null;
                    drawSlot(g, it, sx, sy);
                    if (it != null && mouseInContent && inside(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) hovered = it;
                }
            } else {
                JsonObject pet = d.get("pet") instanceof JsonObject p ? p : null;
                int sx = cx + 6, sy = y + 16;
                g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE, SLOT);
                if (pet != null) {
                    int rgb = 0xFF000000 | RARITY.getOrDefault(String.valueOf(str(pet, "tier")).toUpperCase(), 0xAAAAAA);
                    outline(g, sx, sy, SLOT_SIZE, rgb);
                    icon(g, str(pet, "image"), null, sx + 2, sy + 2);
                    String label = "[Lvl " + (pet.has("level") ? pet.get("level").getAsInt() : 0) + "] " + ChatDisplay.safe(str(pet, "name"), 30);
                    g.text(font, ellipsize(label, colW - SLOT_SIZE - 18), sx + SLOT_SIZE + 5, sy + 6, rgb, false);
                    if (mouseInContent && inside(mouseX, mouseY, sx, sy, colW - 12, SLOT_SIZE)) {
                        JsonObject held = pet.get("heldItem") instanceof JsonObject h ? h : null;
                        hoveredPet = label + (held != null ? "\nHeld: " + ChatDisplay.safe(str(held, "name"), 40) : "");
                    }
                } else {
                    g.text(font, "No active pet", sx + SLOT_SIZE + 5, sy + 6, FAINT, false);
                }
            }
        }
        y += cardH + 8;

        // Weapons | Tools
        int listW = (pw - 28 - 6) / 2;
        List<Item> weapons = items("weapons"), tools = items("tools");
        int rows = Math.max(Math.max(weapons.size(), tools.size()), 1);
        String[] listTitles = {"Weapons (" + weapons.size() + ")", "Tools (" + tools.size() + ")"};
        for (int c = 0; c < 2; c++) {
            List<Item> list = c == 0 ? weapons : tools;
            int cx = px + 14 + c * (listW + 6);
            g.fill(cx, y, cx + listW, y + 18 + rows * ROW + 4, CARD);
            g.text(font, listTitles[c], cx + 6, y + 5, MUTED, false);
            if (list.isEmpty()) g.text(font, c == 0 ? "No weapons found." : "No tools found.", cx + 6, y + 22, FAINT, false);
            for (int i = 0; i < list.size(); i++) {
                Item it = list.get(i);
                int ry = y + 16 + i * ROW;
                drawSlot(g, it, cx + 6, ry);
                g.text(font, ellipsize(LegacyText.strip(it.name()), listW - SLOT_SIZE - 18), cx + SLOT_SIZE + 11, ry + 3, rarityColor(it), false);
                if (it.source() != null) g.text(font, ellipsize(it.source(), listW - SLOT_SIZE - 18), cx + SLOT_SIZE + 11, ry + 12, FAINT, false);
                if (mouseInContent && inside(mouseX, mouseY, cx + 4, ry, listW - 8, ROW - 2)) hovered = it;
            }
        }
        y += 18 + rows * ROW + 8;
        contentHeight = y + (int) scroll - contentTop;
        g.disableScissor();
        clampScroll();

        if (hovered != null) g.setComponentTooltipForNextFrame(font, tooltip(hovered), mouseX, mouseY);
        else if (hoveredPet != null) {
            List<Component> lines = new ArrayList<>();
            for (String l : hoveredPet.split("\n")) lines.add(Component.literal(l));
            g.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
        }
    }

    private List<Component> tooltip(Item it) {
        List<Component> lines = new ArrayList<>();
        lines.add(LegacyText.parse(it.name(), ChatFormatting.WHITE));
        for (String l : it.lore()) lines.add(LegacyText.parse(l, ChatFormatting.GRAY));
        if (it.source() != null) lines.add(Component.literal("Found in: " + it.source()).withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    private void drawSlot(GuiGraphicsExtractor g, Item it, int x, int y) {
        g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT);
        if (it == null) return;
        outline(g, x, y, SLOT_SIZE, rarityColor(it));
        icon(g, it.image(), it.color(), x + 2, y + 2);
        if (it.count() > 1) {
            String n = String.valueOf(it.count());
            g.text(font, n, x + SLOT_SIZE - 1 - font.width(n), y + SLOT_SIZE - 8, TEXT, true);
        }
    }

    private void icon(GuiGraphicsExtractor g, String path, Integer tint, int x, int y) {
        MediaStore.Loaded icon = MediaStore.icon(path, tint);
        if (icon != null && icon.texture != null) ImageScreen.blit(g, icon.texture, x, y, 16, 16, icon.width, icon.height);
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int size, int color) {
        g.fill(x, y, x + size, y + 1, color);
        g.fill(x, y + size - 1, x + size, y + size, color);
        g.fill(x, y, x + 1, y + size, color);
        g.fill(x + size - 1, y, x + size, y + size, color);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && inside(mouseX, mouseY, x, y, w, 16);
        g.fill(x, y, x + w, y + 16, hover ? 0xFF3A3E49 : 0xFF262932);
        g.centeredText(font, label, x + w / 2, y + 4, enabled ? TEXT : FAINT);
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "...") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - (contentBottom - contentTop));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ── Input ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        lastEventClick = System.currentTimeMillis();
        if (event.button() == 0 && click(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubleClick);
    }

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
        if (inside(mx, my, closeX(), py + 8, 46, 16)) {
            onClose();
            return true;
        }
        JsonObject d = data;
        if (d != null && inside(mx, my, websiteX(), py + 8, 64, 16)) {
            String url = str(d, "url");
            if (url != null && url.startsWith("https://skykings.net/")) ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
            return true;
        }
        if (d != null && d.get("profiles") instanceof JsonArray profiles) {
            for (int[] t : tabSpots) {
                if (inside(mx, my, t[0], t[1], t[2], 16) && t[3] < profiles.size()) {
                    fetch(str(profiles.get(t[3]).getAsJsonObject(), "name"));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= vertical * 16;
        return true;
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
