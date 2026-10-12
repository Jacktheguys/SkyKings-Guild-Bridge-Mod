package net.skykings.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /gpv <player> [profile]: skykings.net/profilestats in-game. Tabs for the
 * overview, skills, slayers, dungeons, pets and gear (with each item's full
 * tooltip). The bridge server fetches the stats from the website for players
 * signed in to the bridge; item pictures come from skykings.net.
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
    private static final int GOLD = 0xFFFFAA00;
    private static final int RED = 0xFFF2545B;
    private static final int BAR = 0xFF2A2D37;
    private static final int SLOT_SIZE = 20;
    private static final int ROW = 22;
    private static final String[] TABS = {"Overview", "Skills", "Slayers", "Dungeons", "Pets", "Gear"};

    private static final Map<String, Integer> RARITY = Map.ofEntries(
            Map.entry("COMMON", 0xFFFFFF), Map.entry("UNCOMMON", 0x55FF55), Map.entry("RARE", 0x5555FF),
            Map.entry("EPIC", 0xAA00AA), Map.entry("LEGENDARY", 0xFFAA00), Map.entry("MYTHIC", 0xFF55FF),
            Map.entry("DIVINE", 0x55FFFF), Map.entry("SPECIAL", 0xFF5555), Map.entry("VERY_SPECIAL", 0xFF5555),
            Map.entry("ULTIMATE", 0xAA0000), Map.entry("ADMIN", 0xAA0000));

    private record Item(String name, List<String> lore, String rarity, int count, String image, Integer color, String source) {}

    private final String playerName;
    private volatile JsonObject data;
    private volatile String error;
    private volatile boolean loading;
    private int tab;
    private double scroll;
    private int contentHeight;

    // Clicks: normal events, plus polling for clients (Lunar) that don't pass clicks to mod screens.
    private boolean buttonWasDown = true;
    private long lastEventClick;

    // Layout, worked out each frame
    private int px, py, pw, ph, contentTop, contentBottom;
    private final List<int[]> profileSpots = new ArrayList<>(); // {x, y, w, index}
    private final List<int[]> tabSpots = new ArrayList<>();

    private GearScreen(String playerName) {
        super(Component.literal(playerName));
        this.playerName = playerName;
    }

    /** Opens the screen (on the next tick, see GuildBridgeMod) and starts loading. */
    public static GearScreen load(String player, String profile) {
        GearScreen screen = new GearScreen(player);
        screen.fetch(profile);
        return screen;
    }

    private void fetch(String profile) {
        loading = true;
        error = null;
        boolean asked = GuildBridgeMod.connection().requestProfile(playerName, profile, (msg) -> {
            loading = false;
            if (msg.has("ok") && msg.get("ok").getAsBoolean() && msg.get("data") instanceof JsonObject d) {
                data = d;
                scroll = 0;
            } else {
                error = msg.has("error") ? ChatDisplay.safe(msg.get("error").getAsString(), 120) : "Couldn't load that player.";
            }
        });
        if (!asked) {
            loading = false;
            error = "/gpv works while you're connected to the bridge (on Hypixel).";
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
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static double num(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    private static boolean has(JsonObject o, String key) {
        return o != null && o.get(key) != null && !o.get(key).isJsonNull();
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.get(key) instanceof JsonObject j ? j : null;
    }

    private static List<JsonObject> list(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o != null && o.get(key) instanceof JsonArray arr) arr.forEach((e) -> { if (e.isJsonObject()) out.add(e.getAsJsonObject()); });
        return out;
    }

    private static Item item(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        List<String> lore = new ArrayList<>();
        if (o.get("lore") instanceof JsonArray arr) arr.forEach((l) -> lore.add(l.isJsonNull() ? "" : l.getAsString()));
        Integer color = has(o, "color") ? o.get("color").getAsInt() : null;
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        return new Item(str(o, "name"), lore, str(o, "rarity"), count, str(o, "image"), color, str(o, "source"));
    }

    private static List<Item> items(JsonObject gear, String key) {
        List<Item> out = new ArrayList<>();
        if (gear != null && gear.get(key) instanceof JsonArray arr) arr.forEach((e) -> out.add(item(e)));
        return out;
    }

    private static int rarityColor(String rarity, String name) {
        Integer rgb = rarity == null ? null : RARITY.get(rarity.toUpperCase());
        return 0xFF000000 | (rgb != null ? rgb : LegacyText.firstColor(name, 0xAAAAAA));
    }

    /** 1,607,241,139 -> "1.61B" */
    private static String coins(double n) {
        double[] sizes = {1e12, 1e9, 1e6, 1e3};
        String[] units = {"T", "B", "M", "K"};
        for (int i = 0; i < sizes.length; i++) {
            if (Math.abs(n) >= sizes[i]) return trim(n / sizes[i]) + units[i];
        }
        return String.valueOf(Math.round(n));
    }

    private static String trim(double n) {
        String s = String.format(java.util.Locale.ROOT, "%.2f", n);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private static String whole(double n) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.round(n));
    }

    // ── Layout ──────────────────────────────────────────────────────────

    private void layout() {
        pw = Math.min(width - 20, 480);
        ph = Math.min(height - 20, 330);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        contentTop = py + 66;
        contentBottom = py + ph - 8;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int websiteX() { return px + pw - 14 - 64 - 6 - 46; }
    private int closeX() { return px + pw - 14 - 46; }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout();
        pollClick(mouseX, mouseY);

        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PANEL_EDGE);
        g.fill(px, py, px + pw, py + ph, PANEL);
        g.fill(px, py, px + pw, py + 2, ACCENT);

        JsonObject d = data;
        JsonObject player = obj(d, "player");
        Component title = player != null
                ? LegacyText.parse((has(player, "rank") ? str(player, "rank") + " " : "") + "§f" + str(player, "name"), ChatFormatting.WHITE)
                : Component.literal(playerName);
        g.text(font, title, px + 14, py + 10, TEXT, true);
        if (player != null && has(player, "guild")) {
            g.text(font, "Guild: " + ChatDisplay.safe(str(player, "guild"), 32), px + 14 + font.width(title) + 10, py + 10, MUTED, false);
        }
        button(g, websiteX(), py + 7, 64, "Website", mouseX, mouseY, d != null);
        button(g, closeX(), py + 7, 46, "Close", mouseX, mouseY, true);

        // Profiles, then the section tabs
        profileSpots.clear();
        int x = px + 14;
        List<JsonObject> profiles = list(d, "profiles");
        for (int i = 0; i < profiles.size(); i++) {
            JsonObject p = profiles.get(i);
            String label = ChatDisplay.safe(str(p, "name"), 20);
            boolean selected = p.has("selected") && p.get("selected").getAsBoolean();
            int w = font.width(label);
            boolean hover = inside(mouseX, mouseY, x - 3, py + 25, w + 6, 13);
            g.text(font, label, x, py + 28, selected ? GOLD : hover ? 0xFFC9CDD4 : FAINT, false);
            profileSpots.add(new int[]{x - 3, py + 25, w + 6, i});
            x += w + 12;
        }
        tabSpots.clear();
        x = px + 14;
        for (int i = 0; i < TABS.length; i++) {
            int w = font.width(TABS[i]);
            boolean hover = inside(mouseX, mouseY, x - 4, py + 42, w + 8, 16);
            g.text(font, TABS[i], x, py + 46, i == tab ? TEXT : hover ? 0xFFC9CDD4 : MUTED, false);
            if (i == tab) g.fill(x, py + 57, x + w, py + 58, ACCENT);
            tabSpots.add(new int[]{x - 4, py + 42, w + 8, i});
            x += w + 16;
        }
        g.fill(px + 1, contentTop - 4, px + pw - 1, contentTop - 3, PANEL_EDGE);

        if (d == null || loading) {
            String text = error != null ? error : "Loading " + playerName + "...";
            g.centeredText(font, text, px + pw / 2, (contentTop + contentBottom) / 2 - 4, error != null ? RED : MUTED);
            return;
        }

        g.enableScissor(px + 1, contentTop, px + pw - 1, contentBottom);
        int top = contentTop + 4 - (int) scroll;
        boolean inContent = mouseY >= contentTop && mouseY < contentBottom;
        List<List<Component>> hover = new ArrayList<>(1);
        int bottom = switch (tab) {
            case 0 -> drawOverview(g, obj(d, "overview"), top);
            case 1 -> drawLevels(g, list(d, "skills"), top, "Skills API is off for this profile.", null);
            case 2 -> drawLevels(g, list(d, "slayers"), top, "No slayers yet.", "kills");
            case 3 -> drawDungeons(g, obj(d, "dungeons"), top);
            case 4 -> drawPets(g, list(d, "pets"), top);
            default -> drawGear(g, obj(d, "gear"), top, mouseX, mouseY, inContent, hover);
        };
        contentHeight = bottom + (int) scroll - contentTop;
        g.disableScissor();
        clampScroll();
        if (error != null) g.centeredText(font, error, px + pw / 2, contentBottom - 12, RED);
        if (!hover.isEmpty()) g.setComponentTooltipForNextFrame(font, hover.get(0), mouseX, mouseY);
    }

    /** A grid of stat cards. */
    private int drawOverview(GuiGraphicsExtractor g, JsonObject o, int y) {
        List<String[]> stats = new ArrayList<>();
        stats.add(new String[]{"SkyBlock level", trim(num(o, "level"))});
        stats.add(new String[]{"Purse", coins(num(o, "purse"))});
        stats.add(new String[]{"Bank", has(o, "bank") ? coins(num(o, "bank")) : "API off"});
        stats.add(new String[]{"Skill average", has(o, "skillAverage") ? trim(num(o, "skillAverage")) : "API off"});
        stats.add(new String[]{"Catacombs", trim(num(o, "catacombs"))});
        stats.add(new String[]{"Class average", trim(num(o, "classAverage"))});
        stats.add(new String[]{"Slayer XP", whole(num(o, "slayerXp"))});
        stats.add(new String[]{"Magical power", has(o, "magicalPower") ? whole(num(o, "magicalPower")) : "API off"});
        stats.add(new String[]{"Fairy souls", has(o, "fairySouls") ? str(o, "fairySouls") : "?"});
        stats.add(new String[]{"Pet score", whole(num(o, "petScore"))});
        stats.add(new String[]{"Unique pets", whole(num(o, "uniquePets"))});
        if (has(o, "emblem")) stats.add(new String[]{"Emblem", ChatDisplay.safe(str(o, "emblem"), 24)});
        int cols = 3, gap = 6, cardW = (pw - 28 - gap * (cols - 1)) / cols, cardH = 30;
        for (int i = 0; i < stats.size(); i++) {
            int cx = px + 14 + (i % cols) * (cardW + gap), cy = y + (i / cols) * (cardH + gap);
            g.fill(cx, cy, cx + cardW, cy + cardH, CARD);
            g.text(font, stats.get(i)[0], cx + 7, cy + 5, MUTED, false);
            g.text(font, stats.get(i)[1], cx + 7, cy + 17, TEXT, false);
        }
        return y + ((stats.size() + cols - 1) / cols) * (cardH + gap);
    }

    /** Skills, slayers or dungeon classes: name, level, XP and a progress bar each. */
    private int drawLevels(GuiGraphicsExtractor g, List<JsonObject> rows, int y, String empty, String extraKey) {
        if (rows.isEmpty()) {
            g.text(font, empty, px + 14, y + 4, FAINT, false);
            return y + 16;
        }
        int cols = 2, gap = 6, cardW = (pw - 28 - gap) / cols, cardH = 30;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject r = rows.get(i);
            int cx = px + 14 + (i % cols) * (cardW + gap), cy = y + (i / cols) * (cardH + gap);
            g.fill(cx, cy, cx + cardW, cy + cardH, CARD);
            double level = num(r, "level");
            boolean maxed = r.has("maxed") && r.get("maxed").getAsBoolean();
            g.text(font, ChatDisplay.safe(str(r, "name"), 30), cx + 7, cy + 5, TEXT, false);
            String right = trim(level) + (maxed ? " MAX" : "");
            g.text(font, right, cx + cardW - 7 - font.width(right), cy + 5, maxed ? GOLD : TEXT, false);
            String sub = whole(num(r, "xp")) + " XP" + (extraKey != null ? " · " + whole(num(r, extraKey)) + " " + extraKey : "");
            g.text(font, ellipsize(sub, cardW - 14), cx + 7, cy + 16, FAINT, false);
            int barY = cy + cardH - 4;
            g.fill(cx + 7, barY, cx + cardW - 7, barY + 2, BAR);
            double frac = maxed ? 1 : level - Math.floor(level);
            g.fill(cx + 7, barY, cx + 7 + (int) ((cardW - 14) * frac), barY + 2, maxed ? GOLD : ACCENT);
        }
        return y + ((rows.size() + cols - 1) / cols) * (cardH + gap);
    }

    private int drawDungeons(GuiGraphicsExtractor g, JsonObject dg, int y) {
        if (dg == null) return y;
        List<JsonObject> rows = new ArrayList<>();
        JsonObject cata = obj(dg, "catacombs");
        if (cata != null) {
            JsonObject named = cata.deepCopy();
            named.addProperty("name", "Catacombs");
            rows.add(named);
        }
        rows.addAll(list(dg, "classes"));
        y = drawLevels(g, rows, y, "No dungeons yet.", null);
        String line = "Class average " + trim(num(dg, "classAverage")) + "  ·  Secrets " + whole(num(dg, "secrets"))
                + "  ·  Runs " + whole(num(dg, "runs")) + (has(dg, "selectedClass") ? "  ·  Playing " + str(dg, "selectedClass") : "");
        g.text(font, ellipsize(line, pw - 28), px + 14, y + 2, MUTED, false);
        y += 16;
        y = drawFloors(g, "Catacombs", list(dg, "floors"), y);
        return drawFloors(g, "Master Mode", list(dg, "masterFloors"), y);
    }

    private int drawFloors(GuiGraphicsExtractor g, String title, List<JsonObject> floors, int y) {
        if (floors.isEmpty()) return y;
        int w = pw - 28;
        g.fill(px + 14, y, px + 14 + w, y + 18 + floors.size() * 12 + 2, CARD);
        g.text(font, title, px + 20, y + 5, MUTED, false);
        int c1 = px + 20, c2 = px + 14 + w / 2, c3 = px + 14 + w * 2 / 3, c4 = px + 14 + w * 5 / 6;
        g.text(font, "Runs", c2, y + 5, FAINT, false);
        g.text(font, "Best", c3, y + 5, FAINT, false);
        g.text(font, "Fastest S", c4, y + 5, FAINT, false);
        for (int i = 0; i < floors.size(); i++) {
            JsonObject f = floors.get(i);
            int ry = y + 18 + i * 12;
            g.text(font, ellipsize(ChatDisplay.safe(str(f, "name"), 30), c2 - c1 - 6), c1, ry, TEXT, false);
            g.text(font, whole(num(f, "completions")), c2, ry, TEXT, false);
            g.text(font, whole(num(f, "bestScore")), c3, ry, TEXT, false);
            double ms = num(f, "fastestS");
            g.text(font, ms > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d", (long) (ms / 60000), (long) (ms / 1000) % 60) : "-", c4, ry, TEXT, false);
        }
        return y + 18 + floors.size() * 12 + 10;
    }

    private int drawPets(GuiGraphicsExtractor g, List<JsonObject> pets, int y) {
        if (pets.isEmpty()) {
            g.text(font, "No pets.", px + 14, y + 4, FAINT, false);
            return y + 16;
        }
        int cols = 2, gap = 6, w = (pw - 28 - gap) / cols;
        for (int i = 0; i < pets.size(); i++) {
            JsonObject p = pets.get(i);
            int cx = px + 14 + (i % cols) * (w + gap), cy = y + (i / cols) * ROW;
            int rgb = rarityColor(str(p, "tier"), null);
            g.fill(cx, cy, cx + SLOT_SIZE, cy + SLOT_SIZE, SLOT);
            outline(g, cx, cy, SLOT_SIZE, rgb);
            icon(g, str(p, "image"), null, cx + 2, cy + 2);
            boolean active = p.has("active") && p.get("active").getAsBoolean();
            String label = "[Lvl " + (int) num(p, "level") + "] " + ChatDisplay.safe(str(p, "name"), 30) + (active ? " (active)" : "");
            g.text(font, ellipsize(label, w - SLOT_SIZE - 10), cx + SLOT_SIZE + 5, cy + 2, rgb, false);
            if (has(p, "held")) g.text(font, ellipsize("Held: " + ChatDisplay.safe(str(p, "held"), 40), w - SLOT_SIZE - 10), cx + SLOT_SIZE + 5, cy + 11, FAINT, false);
        }
        return y + ((pets.size() + cols - 1) / cols) * ROW + 4;
    }

    private int drawGear(GuiGraphicsExtractor g, JsonObject gear, int y, int mouseX, int mouseY, boolean inContent, List<List<Component>> hover) {
        if (gear == null) {
            g.text(font, "Inventory API is off for this profile.", px + 14, y + 4, FAINT, false);
            return y + 16;
        }
        // Armor | Equipment
        int colW = (pw - 28 - 6) / 2, cardH = 14 + SLOT_SIZE + 10;
        String[] titles = {"Armor", "Equipment"};
        for (int c = 0; c < 2; c++) {
            int cx = px + 14 + c * (colW + 6);
            g.fill(cx, y, cx + colW, y + cardH, CARD);
            g.text(font, titles[c], cx + 6, y + 5, MUTED, false);
            List<Item> slots = items(gear, c == 0 ? "armor" : "equipment");
            for (int i = 0; i < 4; i++) {
                int sx = cx + 6 + i * (SLOT_SIZE + 3), sy = y + 16;
                Item it = i < slots.size() ? slots.get(i) : null;
                drawSlot(g, it, sx, sy);
                if (it != null && inContent && inside(mouseX, mouseY, sx, sy, SLOT_SIZE, SLOT_SIZE)) hover.add(tooltip(it));
            }
        }
        y += cardH + 8;
        // Weapons | Tools
        List<Item> weapons = items(gear, "weapons"), tools = items(gear, "tools");
        int rows = Math.max(Math.max(weapons.size(), tools.size()), 1);
        for (int c = 0; c < 2; c++) {
            List<Item> list = c == 0 ? weapons : tools;
            int cx = px + 14 + c * (colW + 6);
            g.fill(cx, y, cx + colW, y + 18 + rows * ROW + 4, CARD);
            g.text(font, (c == 0 ? "Weapons (" : "Tools (") + list.size() + ")", cx + 6, y + 5, MUTED, false);
            for (int i = 0; i < list.size(); i++) {
                Item it = list.get(i);
                if (it == null) continue;
                int ry = y + 16 + i * ROW;
                drawSlot(g, it, cx + 6, ry);
                g.text(font, ellipsize(LegacyText.strip(it.name()), colW - SLOT_SIZE - 18), cx + SLOT_SIZE + 11, ry + 3, rarityColor(it.rarity(), it.name()), false);
                if (it.source() != null) g.text(font, ellipsize(it.source(), colW - SLOT_SIZE - 18), cx + SLOT_SIZE + 11, ry + 12, FAINT, false);
                if (inContent && inside(mouseX, mouseY, cx + 4, ry, colW - 8, ROW - 2)) hover.add(tooltip(it));
            }
        }
        return y + 18 + rows * ROW + 8;
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
        outline(g, x, y, SLOT_SIZE, rarityColor(it.rarity(), it.name()));
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
        if (inside(mx, my, closeX(), py + 7, 46, 16)) {
            onClose();
            return true;
        }
        JsonObject d = data;
        if (d != null && inside(mx, my, websiteX(), py + 7, 64, 16)) {
            String url = str(d, "url");
            if (url != null && url.startsWith("https://skykings.net/")) ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
            return true;
        }
        for (int[] t : tabSpots) {
            if (inside(mx, my, t[0], t[1], t[2], 16)) {
                tab = t[3];
                scroll = 0;
                return true;
            }
        }
        if (d != null && !loading) {
            List<JsonObject> profiles = list(d, "profiles");
            for (int[] t : profileSpots) {
                if (inside(mx, my, t[0], t[1], t[2], 13) && t[3] < profiles.size()) {
                    fetch(str(profiles.get(t[3]), "name"));
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
