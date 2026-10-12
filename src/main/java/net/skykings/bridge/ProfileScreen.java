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
import java.util.Locale;
import java.util.Map;

/**
 * /gpv <player> [profile]: skykings.net/profilestats in-game, laid out like the
 * website: skin and quick stats on the left; name, profiles, SkyBlock level and
 * skills on top; then tabs (Gear, Accessories, Pets, Dungeons, Slayers). Items
 * show their full tooltip on hover. The bridge fetches the stats from the website
 * for players signed in to it; pictures come from skykings.net.
 */
public final class ProfileScreen extends Screen {
    // The website's colours
    private static final int PAGE = 0xF20D0F14;
    private static final int PAGE_EDGE = 0xFF262A33;
    private static final int CARD = 0xFF15171D;
    private static final int CARD_EDGE = 0xFF252932;
    private static final int TILE = 0xFF1B1E26;
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9AA0AB;
    private static final int FAINT = 0xFF5C626D;
    private static final int ORANGE = 0xFFF5A623;
    private static final int BAR = 0xFF2B2F38;
    private static final int RED = 0xFFF2545B;
    private static final int SLOT = 20;

    private static final String[] TABS = {"Gear", "Accessories", "Pets", "Dungeons", "Slayers"};
    private static final String[] TAB_ICONS = {"diamond_chestplate", "diamond", "bone", "wither_skeleton_skull", "golden_sword"};

    private static final Map<String, String> SKILL_ICON = Map.ofEntries(
            Map.entry("FARMING", "golden_hoe"), Map.entry("MINING", "stone_pickaxe"), Map.entry("COMBAT", "stone_sword"),
            Map.entry("FORAGING", "jungle_sapling"), Map.entry("FISHING", "fishing_rod"), Map.entry("ENCHANTING", "enchanting_table"),
            Map.entry("ALCHEMY", "brewing_stand"), Map.entry("TAMING", "bone"), Map.entry("CARPENTRY", "crafting_table"),
            Map.entry("HUNTING", "lead"), Map.entry("RUNECRAFTING", "magma_cream"), Map.entry("SOCIAL", "emerald"));
    private static final Map<String, Integer> SKILL_COLOR = Map.ofEntries(
            Map.entry("FARMING", 0x7bd389), Map.entry("MINING", 0x7aa7ff), Map.entry("COMBAT", 0xff7a7a),
            Map.entry("FORAGING", 0x5fd3a8), Map.entry("FISHING", 0x5cc8ff), Map.entry("ENCHANTING", 0xc49bff),
            Map.entry("ALCHEMY", 0xffae5c), Map.entry("TAMING", 0xb48cff), Map.entry("CARPENTRY", 0xffd166),
            Map.entry("HUNTING", 0xff9152), Map.entry("RUNECRAFTING", 0xff7ac0), Map.entry("SOCIAL", 0x4fd8e8));
    private static final Map<String, String> SLAYER_ICON = Map.of("zombie", "rotten_flesh", "spider", "spider_eye", "wolf", "mutton",
            "enderman", "ender_pearl", "blaze", "blaze_rod", "vampire", "redstone");
    private static final Map<String, String> CLASS_ICON = Map.of("healer", "splash_potion", "mage", "blaze_rod", "berserk", "iron_sword",
            "archer", "bow", "tank", "leather_chestplate");
    private static final Map<String, Integer> CLASS_COLOR = Map.of("healer", 0xff7ac0, "mage", 0x7aa7ff, "berserk", 0xff7a7a,
            "archer", 0xffd166, "tank", 0x7bd389);
    private static final Map<String, Integer> RARITY = Map.ofEntries(
            Map.entry("COMMON", 0xFFFFFF), Map.entry("UNCOMMON", 0x55FF55), Map.entry("RARE", 0x5555FF),
            Map.entry("EPIC", 0xAA00AA), Map.entry("LEGENDARY", 0xFFAA00), Map.entry("MYTHIC", 0xFF55FF),
            Map.entry("DIVINE", 0x55FFFF), Map.entry("SPECIAL", 0xFF5555), Map.entry("VERY_SPECIAL", 0xFF5555),
            Map.entry("ULTIMATE", 0xAA0000), Map.entry("ADMIN", 0xAA0000));
    private static final String[] RARITY_ORDER = {"VERY_SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE", "UNCOMMON", "COMMON"};

    private record Item(String name, List<String> lore, String rarity, int count, String image, Integer color, String source, boolean inactive) {}

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

    // Layout (worked out each frame) and what can be clicked, from the last frame
    private int px, py, pw, ph, top, bottom;
    private final List<int[]> profileSpots = new ArrayList<>(); // {x, y, w, h, index}
    private final List<int[]> tabSpots = new ArrayList<>();

    private ProfileScreen(String playerName) {
        super(Component.literal(playerName));
        this.playerName = playerName;
    }

    /** Opens the screen (on the next tick, see GuildBridgeMod) and starts loading. */
    public static ProfileScreen load(String player, String profile) {
        ProfileScreen screen = new ProfileScreen(player);
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

    private static boolean flag(JsonObject o, String key) {
        return has(o, key) && o.get(key).getAsBoolean();
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
        return new Item(str(o, "name"), lore, str(o, "rarity"), has(o, "count") ? o.get("count").getAsInt() : 1,
                str(o, "image"), has(o, "color") ? o.get("color").getAsInt() : null,
                has(o, "source") ? ChatDisplay.safe(str(o, "source"), 40) : null, flag(o, "inactive"));
    }

    private static List<Item> items(JsonObject o, String key) {
        List<Item> out = new ArrayList<>();
        if (o != null && o.get(key) instanceof JsonArray arr) arr.forEach((e) -> out.add(item(e)));
        return out;
    }

    private static int rarityColor(String rarity, String name) {
        Integer rgb = rarity == null ? null : RARITY.get(rarity.toUpperCase(Locale.ROOT));
        return 0xFF000000 | (rgb != null ? rgb : LegacyText.firstColor(name, 0xAAAAAA));
    }

    /** 464,566,008 -> "464.57M", like the website. */
    private static String fmt(double n) {
        double[] sizes = {1e12, 1e9, 1e6, 1e3};
        String[] units = {"T", "B", "M", "K"};
        for (int i = 0; i < sizes.length; i++) {
            if (Math.abs(n) >= sizes[i]) return String.format(Locale.ROOT, "%.2f", n / sizes[i]).replaceAll("\\.?0+$", "") + units[i];
        }
        return String.valueOf(Math.round(n));
    }

    /** One decimal for XP under bars: "262.3K". */
    private static String fmt1(double n) {
        double[] sizes = {1e12, 1e9, 1e6, 1e3};
        String[] units = {"T", "B", "M", "K"};
        for (int i = 0; i < sizes.length; i++) {
            if (Math.abs(n) >= sizes[i]) return String.format(Locale.ROOT, "%.1f", n / sizes[i]) + units[i];
        }
        return String.valueOf(Math.round(n));
    }

    private static String full(double n) {
        return String.format(Locale.ROOT, "%,d", Math.round(n));
    }

    private static String vanilla(String name) {
        return "/images/minecraft/minecraft_" + name + ".png";
    }

    // ── Drawing helpers ─────────────────────────────────────────────────

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void card(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill) {
        g.fill(x, y, x + w, y + h, CARD_EDGE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** Text drawn at a scale (e.g. 0.75 for small labels, 1.5 for the name). */
    private void scaled(GuiGraphicsExtractor g, Component text, int x, int y, float scale, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, color, false);
        g.pose().popMatrix();
    }

    private void small(GuiGraphicsExtractor g, String text, int x, int y, int color) {
        scaled(g, Component.literal(text), x, y, 0.75f, color);
    }

    private int smallWidth(String text) {
        return (int) Math.ceil(font.width(text) * 0.75f);
    }

    private void icon(GuiGraphicsExtractor g, String path, Integer tint, int x, int y, int size) {
        MediaStore.Loaded icon = MediaStore.icon(path, tint);
        if (icon != null && icon.texture != null) ImageScreen.blit(g, icon.texture, x, y, size, size, icon.width, icon.height);
    }

    private static void bar(GuiGraphicsExtractor g, int x, int y, int w, double progress, int color) {
        g.fill(x, y, x + w, y + 3, BAR);
        g.fill(x, y, x + (int) (w * Math.max(0, Math.min(1, progress))), y + 3, color);
    }

    private String ellipsize(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String cut = text;
        while (!cut.isEmpty() && font.width(cut + "...") > maxWidth) cut = cut.substring(0, cut.length() - 1);
        return cut + "...";
    }

    // ── Layout ──────────────────────────────────────────────────────────

    private void layout() {
        pw = Math.min(width - 12, 640);
        ph = height - 12;
        px = (width - pw) / 2;
        py = 6;
        top = py + 24;
        bottom = py + ph - 4;
    }

    private int websiteX() { return px + pw - 8 - 52 - 4 - 40; }
    private int closeX() { return px + pw - 8 - 40; }

    // ── Frame ───────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        layout();
        pollClick(mouseX, mouseY);

        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PAGE_EDGE);
        g.fill(px, py, px + pw, py + ph, PAGE);
        Component brand = Component.literal("SkyKings").withStyle((s) -> s.withBold(true));
        g.text(font, brand, px + 10, py + 8, ORANGE, false);
        g.text(font, "Profile Stats", px + 10 + font.width(brand) + 6, py + 8, MUTED, false);
        button(g, websiteX(), py + 5, 52, "Website", mouseX, mouseY, data != null, false);
        button(g, closeX(), py + 5, 40, "Close", mouseX, mouseY, true, false);
        g.fill(px + 1, top - 2, px + pw - 1, top - 1, PAGE_EDGE);

        JsonObject d = data;
        if (d == null || loading) {
            String text = error != null ? error : "Loading " + playerName + "...";
            g.centeredText(font, text, px + pw / 2, (top + bottom) / 2 - 4, error != null ? RED : MUTED);
            return;
        }

        g.enableScissor(px + 1, top, px + pw - 1, bottom);
        try {
            drawContent(g, d, mouseX, mouseY);
        } catch (RuntimeException e) {
            // An answer the screen doesn't understand must never crash the game.
            if (!drawFailed) GuildBridgeMod.LOG.warn("Couldn't draw /gpv for {}", playerName, e);
            drawFailed = true;
            error = "Couldn't show this profile.";
        } finally {
            g.disableScissor();
        }
        clampScroll();
        if (error != null) g.centeredText(font, error, px + pw / 2, bottom - 12, RED);
        if (hoverLines != null) g.setComponentTooltipForNextFrame(font, hoverLines, mouseX, mouseY);
        hoverLines = null;
    }

    private boolean drawFailed;
    private List<Component> hoverLines;

    private void drawContent(GuiGraphicsExtractor g, JsonObject d, int mouseX, int mouseY) {
        boolean inContent = mouseY >= top && mouseY < bottom;
        List<List<Component>> hover = new ArrayList<>(1);
        int y0 = top + 6 - (int) scroll;
        boolean sidebar = pw >= 430;
        int sideW = sidebar ? 112 : 0;
        int mainX = px + 8 + (sidebar ? sideW + 8 : 0);
        int mainW = px + pw - 8 - mainX;

        int sideBottom = y0;
        if (sidebar) sideBottom = drawSidebar(g, d, px + 8, y0, sideW);
        int y = drawHeader(g, d, mainX, y0, mainW, mouseX, mouseY);
        if (!sidebar) y = drawQuickStats(g, obj(d, "overview"), mainX, y, mainW, 5) + 6;
        y = drawLevelBar(g, obj(d, "overview"), mainX, y, mainW) + 6;
        y = drawSkills(g, list(d, "skills"), mainX, y, mainW) + 8;
        y = drawTabs(g, mainX, y, mainW, mouseX, mouseY) + 8;
        y = switch (tab) {
            case 0 -> drawGear(g, obj(d, "gear"), mainX, y, mainW, mouseX, mouseY, inContent, hover);
            case 1 -> drawAccessories(g, obj(d, "accessories"), mainX, y, mainW, mouseX, mouseY, inContent, hover);
            case 2 -> drawPets(g, list(d, "pets"), mainX, y, mainW, mouseX, mouseY, inContent, hover);
            case 3 -> drawDungeons(g, obj(d, "dungeons"), mainX, y, mainW);
            default -> drawSlayers(g, list(d, "slayers"), mainX, y, mainW);
        };
        contentHeight = Math.max(y, sideBottom) + 8 + (int) scroll - top;
        if (!hover.isEmpty()) hoverLines = hover.get(0);
    }

    // ── Sidebar: skin + quick stats ─────────────────────────────────────

    private int drawSidebar(GuiGraphicsExtractor g, JsonObject d, int x, int y, int w) {
        JsonObject player = obj(d, "player");
        int skinH = 120;
        card(g, x, y, w, skinH, CARD);
        String uuid = str(player, "uuid");
        if (uuid != null && uuid.matches("[0-9a-f]{32}")) {
            MediaStore.Loaded body = MediaStore.icon("/mc/mh/body/" + uuid + "/200", null);
            if (body != null && body.texture != null && body.height > 0) {
                int h = skinH - 12, bw = h * body.width / body.height;
                ImageScreen.blit(g, body.texture, x + (w - bw) / 2, y + 6, bw, h, body.width, body.height);
            }
        }
        y += skinH + 6;
        int statsH = quickStatsHeight(2);
        card(g, x, y, w, 16 + statsH + 6, CARD);
        small(g, "QUICK STATS", x + 7, y + 6, MUTED);
        drawQuickStats(g, obj(d, "overview"), x + 5, y + 16, w - 10, 2);
        return y + 16 + statsH + 6;
    }

    private static final int QUICK_TILES = 10, TILE_H = 34, TILE_GAP = 4;

    private static int quickStatsHeight(int cols) {
        return ((QUICK_TILES + cols - 1) / cols) * (TILE_H + TILE_GAP) - TILE_GAP;
    }

    /** The website's Quick Stats tiles: icon, coloured value, small label. */
    private int drawQuickStats(GuiGraphicsExtractor g, JsonObject o, int x, int y, int w, int cols) {
        List<Object[]> tiles = new ArrayList<>();
        tiles.add(new Object[]{"emerald", "NETWORTH", has(o, "networth") ? fmt(num(o, "networth")) : "-", 0x7dffa0});
        tiles.add(new Object[]{"gold_nugget", "PURSE", fmt(num(o, "purse")), 0xf1b24a});
        tiles.add(new Object[]{"gold_ingot", "BANK", has(o, "bank") ? fmt(num(o, "bank")) : "API off", 0xffd166});
        tiles.add(new Object[]{"book", "SKILL AVG", has(o, "skillAverage") ? String.format(Locale.ROOT, "%.2f", num(o, "skillAverage")) : "API off", 0x7aa7ff});
        tiles.add(new Object[]{"wither_skeleton_skull", "CATACOMBS", String.valueOf((int) num(o, "catacombs")), 0xc49bff});
        tiles.add(new Object[]{"rotten_flesh", "SLAYER XP", fmt(num(o, "slayerXp")), 0xff7a7a});
        tiles.add(new Object[]{"diamond", "MAG. POWER", has(o, "magicalPower") ? full(num(o, "magicalPower")) : "-", 0x5cc8ff});
        JsonObject souls = obj(o, "fairySouls");
        tiles.add(new Object[]{"pink_dye", "FAIRY SOULS", souls == null ? "-" : (int) num(souls, "collected") + (has(souls, "max") ? "/" + (int) num(souls, "max") : ""), 0xff8fcf});
        tiles.add(new Object[]{"clock", "JOINED", has(o, "joined") ? age(num(o, "joined")) : "-", 0xa8d8a8});
        tiles.add(new Object[]{"bone", "PET SCORE", full(num(o, "petScore")), 0xffae5c});
        int gap = TILE_GAP, tw = (w - gap * (cols - 1)) / cols, th = TILE_H;
        for (int i = 0; i < tiles.size(); i++) {
            Object[] t = tiles.get(i);
            int tx = x + (i % cols) * (tw + gap), ty = y + (i / cols) * (th + gap);
            card(g, tx, ty, tw, th, TILE);
            icon(g, vanilla((String) t[0]), null, tx + (tw - 8) / 2, ty + 3, 8);
            String value = (String) t[2];
            g.text(font, value, tx + (tw - font.width(value)) / 2, ty + 13, 0xFF000000 | (int) t[3], true);
            String label = (String) t[1];
            small(g, label, tx + (tw - smallWidth(label)) / 2, ty + 25, FAINT);
        }
        return y + quickStatsHeight(cols);
    }

    /** Mixes a colour towards white (amount 0..1). */
    private static int lighten(int rgb, float amount) {
        int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r += (int) ((255 - r) * amount);
        gr += (int) ((255 - gr) * amount);
        b += (int) ((255 - b) * amount);
        return (r << 16) | (gr << 8) | b;
    }

    private static String age(double joinedMs) {
        double days = (System.currentTimeMillis() - joinedMs) / 86_400_000.0;
        if (days >= 365) return (int) (days / 365) + " years";
        if (days >= 30) return (int) (days / 30) + " months";
        return (int) Math.max(0, days) + " days";
    }

    // ── Header: name, chips, profiles ───────────────────────────────────

    private int drawHeader(GuiGraphicsExtractor g, JsonObject d, int x, int y, int w, int mouseX, int mouseY) {
        JsonObject player = obj(d, "player");
        Component name = LegacyText.parse((has(player, "rank") ? str(player, "rank") + " " : "§7") + "§b" + str(player, "name"), ChatFormatting.WHITE)
                .copy().withStyle((s) -> s.withBold(true));
        scaled(g, name, x, y + 2, 1.5f, TEXT);

        // Profiles, right-aligned pills (the selected one orange, like the site)
        profileSpots.clear();
        List<JsonObject> profiles = list(d, "profiles");
        int rx = x + w;
        for (int i = profiles.size() - 1; i >= 0; i--) {
            JsonObject p = profiles.get(i);
            String label = ChatDisplay.safe(str(p, "name"), 20);
            int bw = font.width(label) + 12;
            rx -= bw;
            boolean selected = flag(p, "selected");
            boolean hover = inside(mouseX, mouseY, rx, y, bw, 15);
            g.fill(rx, y, rx + bw, y + 15, selected ? ORANGE : hover ? 0xFF2E323C : TILE);
            g.text(font, label, rx + 6, y + 4, selected ? 0xFF1A1A1A : TEXT, false);
            profileSpots.add(new int[]{rx, y, bw, 15, i});
            rx -= 4;
        }
        y += 20;

        // Chips: guild, booster cookie, co-op
        JsonObject o = obj(d, "overview");
        List<String[]> chips = new ArrayList<>();
        if (has(player, "guild")) chips.add(new String[]{"shield", ChatDisplay.safe(str(player, "guild"), 32)});
        if (flag(o, "cookieBuff")) chips.add(new String[]{"cookie", "Booster Cookie active"});
        if (o != null && o.get("coop") instanceof JsonArray coop && !coop.isEmpty()) {
            List<String> names = new ArrayList<>();
            coop.forEach((n) -> names.add(ChatDisplay.safe(n.getAsString(), 16)));
            chips.add(new String[]{"player_head", "Co-op with " + String.join(", ", names)});
        }
        int cx = x;
        for (String[] chip : chips) {
            String text = ellipsize(chip[1], w - 20);
            int cw = font.width(text) + 22;
            if (cx + cw > x + w) break;
            g.fill(cx, y, cx + cw, y + 14, CARD_EDGE);
            g.fill(cx + 1, y + 1, cx + cw - 1, y + 13, chip[0].equals("cookie") ? 0xFF2A1F12 : TILE);
            icon(g, vanilla(chip[0]), null, cx + 4, y + 3, 8);
            g.text(font, text, cx + 16, y + 3, chip[0].equals("cookie") ? ORANGE : TEXT, false);
            cx += cw + 4;
        }
        return y + (chips.isEmpty() ? 0 : 18);
    }

    private int drawLevelBar(GuiGraphicsExtractor g, JsonObject o, int x, int y, int w) {
        int h = 26;
        card(g, x, y, w, h, CARD);
        int base = LegacyText.firstColor("§" + (has(o, "levelColor") ? str(o, "levelColor") : "f"), 0xFFFFFF);
        int color = 0xFF000000 | lighten(base, 0.35f); // dark level colours (e.g. dark red) stay readable
        String level = String.valueOf((int) num(o, "level"));
        int bw = Math.max(22, font.width(level) + 10);
        g.fill(x + 5, y + 5, x + 5 + bw, y + h - 5, (base & 0x00FFFFFF) | 0x50000000);
        g.text(font, level, x + 5 + (bw - font.width(level)) / 2, y + 9, color, true);
        int bx = x + 5 + bw + 8;
        g.text(font, "SkyBlock Level", bx, y + 5, TEXT, false);
        JsonObject xp = obj(o, "levelXp");
        String right = xp == null ? "" : (int) num(xp, "current") + "/" + (int) num(xp, "next") + " XP";
        small(g, right, x + w - 6 - smallWidth(right), y + 6, MUTED);
        double progress = xp == null || num(xp, "next") <= 0 ? 0 : num(xp, "current") / num(xp, "next");
        bar(g, bx, y + 17, x + w - 6 - bx, progress, 0xFFC0272D);
        return y + h;
    }

    private int drawSkills(GuiGraphicsExtractor g, List<JsonObject> skills, int x, int y, int w) {
        if (skills.isEmpty()) {
            card(g, x, y, w, 20, CARD);
            g.text(font, "Skills API is off for this profile.", x + 8, y + 6, FAINT, false);
            return y + 20;
        }
        int cols = w >= 420 ? 4 : w >= 300 ? 3 : 2;
        int gap = 4, cw = (w - gap * (cols - 1)) / cols, chh = 30;
        for (int i = 0; i < skills.size(); i++) {
            JsonObject s = skills.get(i);
            String key = String.valueOf(str(s, "key"));
            int color = 0xFF000000 | SKILL_COLOR.getOrDefault(key, 0x5aa9ff);
            levelCard(g, s, x + (i % cols) * (cw + gap), y + (i / cols) * (chh + gap), cw, chh,
                    vanilla(SKILL_ICON.getOrDefault(key, "book")), color, str(s, "name"));
        }
        return y + ((skills.size() + cols - 1) / cols) * (chh + gap) - gap;
    }

    /** The website's level card: icon, name, level/max, coloured bar, and "MAX 464.57M XP" or "262.3K / 700.0K XP". */
    private void levelCard(GuiGraphicsExtractor g, JsonObject s, int x, int y, int w, int h, String iconPath, int color, String name) {
        card(g, x, y, w, h, CARD);
        icon(g, iconPath, null, x + 5, y + (h - 14) / 2, 14);
        int tx = x + 23, tw = w - 23 - 5;
        g.text(font, ellipsize(ChatDisplay.safe(name, 30), tw - 34), tx, y + 4, TEXT, false);
        boolean maxed = flag(s, "maxed");
        String lvl = String.valueOf((int) num(s, "level"));
        String max = has(s, "maxLevel") ? "/" + (int) num(s, "maxLevel") : "";
        int mw = smallWidth(max);
        g.text(font, lvl, x + w - 5 - mw - font.width(lvl), y + 4, TEXT, false);
        if (!max.isEmpty()) small(g, max, x + w - 5 - mw, y + 6, MUTED);
        bar(g, tx, y + 14, tw, maxed ? 1 : num(s, "progress"), color);
        if (maxed) {
            small(g, "MAX", tx, y + 20, ORANGE);
            small(g, fmt(num(s, "xp")) + " XP", tx + smallWidth("MAX "), y + 20, MUTED);
        } else {
            String xp = fmt1(num(s, "xpCurrent")) + (has(s, "xpForNext") ? " / " + fmt1(num(s, "xpForNext")) : "") + " XP";
            small(g, xp, tx, y + 20, MUTED);
        }
    }

    private int drawTabs(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
        tabSpots.clear();
        card(g, x, y, w, 24, CARD);
        int tx = x + 4;
        for (int i = 0; i < TABS.length; i++) {
            int bw = font.width(TABS[i]) + 24;
            boolean selected = i == tab;
            boolean hover = inside(mouseX, mouseY, tx, y + 4, bw, 16);
            if (selected || hover) g.fill(tx, y + 4, tx + bw, y + 20, selected ? ORANGE : 0xFF262A33);
            icon(g, vanilla(TAB_ICONS[i]), null, tx + 5, y + 8, 8);
            g.text(font, TABS[i], tx + 17, y + 8, selected ? 0xFF1A1A1A : TEXT, false);
            tabSpots.add(new int[]{tx, y + 4, bw, 16, i});
            tx += bw + 4;
        }
        return y + 24;
    }

    // ── Tabs ────────────────────────────────────────────────────────────

    private int drawGear(GuiGraphicsExtractor g, JsonObject gear, int x, int y, int w, int mouseX, int mouseY, boolean inContent, List<List<Component>> hover) {
        if (gear == null) return emptyCard(g, x, y, w, "Inventory API is off for this profile.");
        JsonObject pet = null;
        for (JsonObject p : list(data, "pets")) if (flag(p, "active")) pet = p;
        int gap = 6, cw = (w - gap * 2) / 3, ch = 46;
        String[] titles = {"Armor", "Equipment", "Active pet"};
        for (int c = 0; c < 3; c++) {
            int cx = x + c * (cw + gap);
            card(g, cx, y, cw, ch, CARD);
            g.text(font, titles[c], cx + 7, y + 6, TEXT, false);
            if (c < 2) {
                List<Item> slots = items(gear, c == 0 ? "armor" : "equipment");
                for (int i = 0; i < 4; i++) {
                    int sx = cx + 7 + i * (SLOT + 3), sy = y + 19;
                    Item it = i < slots.size() ? slots.get(i) : null;
                    slot(g, it, sx, sy);
                    if (it != null && inContent && inside(mouseX, mouseY, sx, sy, SLOT, SLOT)) hover.add(tooltip(it));
                }
            } else if (pet != null) {
                int rgb = rarityColor(str(pet, "tier"), null);
                petSlot(g, pet, cx + 7, y + 19);
                small(g, ellipsize("[Lvl " + (int) num(pet, "level") + "] " + ChatDisplay.safe(str(pet, "name"), 30), (int) ((cw - SLOT - 14) / 0.75f)), cx + 11 + SLOT, y + 26, rgb);
            } else {
                small(g, "No active pet", cx + 7, y + 25, FAINT);
            }
        }
        y += ch + gap;
        List<Item> weapons = items(gear, "weapons"), tools = items(gear, "tools");
        int lw = (w - gap) / 2, rows = Math.max(Math.max(weapons.size(), tools.size()), 1), rowH = 22;
        for (int c = 0; c < 2; c++) {
            List<Item> list = c == 0 ? weapons : tools;
            int cx = x + c * (lw + gap);
            card(g, cx, y, lw, 20 + rows * rowH + 4, CARD);
            g.text(font, c == 0 ? "Weapons" : "Tools", cx + 7, y + 6, TEXT, false);
            String count = String.valueOf(list.size());
            small(g, count, cx + lw - 7 - smallWidth(count), y + 7, FAINT);
            for (int i = 0; i < list.size(); i++) {
                Item it = list.get(i);
                if (it == null) continue;
                int ry = y + 19 + i * rowH;
                slot(g, it, cx + 7, ry);
                g.text(font, ellipsize(LegacyText.strip(it.name()), lw - SLOT - 20), cx + SLOT + 12, ry + 2, rarityColor(it.rarity(), it.name()), false);
                if (it.source() != null) small(g, it.source(), cx + SLOT + 12, ry + 12, FAINT);
                if (inContent && inside(mouseX, mouseY, cx + 5, ry, lw - 10, rowH - 2)) hover.add(tooltip(it));
            }
        }
        return y + 20 + rows * rowH + 4;
    }

    private int drawAccessories(GuiGraphicsExtractor g, JsonObject acc, int x, int y, int w, int mouseX, int mouseY, boolean inContent, List<List<Component>> hover) {
        if (acc == null) return emptyCard(g, x, y, w, "Inventory API is off for this profile.");
        // Summary line + rarity chips
        card(g, x, y, w, 36, CARD);
        g.text(font, "Magical Power ", x + 8, y + 6, MUTED, false);
        g.text(font, full(num(acc, "magicalPower")), x + 8 + font.width("Magical Power "), y + 6, 0xFF5CC8FF, false);
        String right = (has(acc, "selectedPower") ? str(acc, "selectedPower") + " power · " : "") + (int) num(acc, "active") + " active";
        g.text(font, right, x + w - 8 - font.width(right), y + 6, MUTED, false);
        JsonObject counts = obj(acc, "rarityCounts");
        int cx = x + 8;
        for (String r : RARITY_ORDER) {
            if (!has(counts, r)) continue;
            String label = (int) num(counts, r) + " " + r.charAt(0) + r.substring(1).toLowerCase(Locale.ROOT).replace("_", " ");
            int cw = smallWidth(label) + 8;
            if (cx + cw > x + w - 8) break;
            g.fill(cx, y + 20, cx + cw, y + 31, TILE);
            small(g, label, cx + 4, y + 23, 0xFF000000 | RARITY.get(r));
            cx += cw + 3;
        }
        y += 40;
        // Every accessory as a slot, like the website's grid
        List<Item> all = items(acc, "items");
        int per = Math.max(1, (w - 12) / (SLOT + 3));
        int rows = (all.size() + per - 1) / per;
        card(g, x, y, w, rows * (SLOT + 3) + 10, CARD);
        for (int i = 0; i < all.size(); i++) {
            Item it = all.get(i);
            if (it == null) continue;
            int sx = x + 6 + (i % per) * (SLOT + 3), sy = y + 6 + (i / per) * (SLOT + 3);
            slot(g, it, sx, sy);
            if (it.inactive()) g.fill(sx, sy, sx + SLOT, sy + SLOT, 0xA0000000);
            if (inContent && inside(mouseX, mouseY, sx, sy, SLOT, SLOT)) hover.add(tooltip(it));
        }
        return y + rows * (SLOT + 3) + 10;
    }

    private int drawPets(GuiGraphicsExtractor g, List<JsonObject> pets, int x, int y, int w, int mouseX, int mouseY, boolean inContent, List<List<Component>> hover) {
        if (pets.isEmpty()) return emptyCard(g, x, y, w, "No pets.");
        int cols = w >= 360 ? 3 : 2, gap = 4, cw = (w - gap * (cols - 1)) / cols, ch = 26;
        for (int i = 0; i < pets.size(); i++) {
            JsonObject p = pets.get(i);
            int cx = x + (i % cols) * (cw + gap), cy = y + (i / cols) * (ch + gap);
            card(g, cx, cy, cw, ch, flag(p, "active") ? 0xFF1F2430 : CARD);
            petSlot(g, p, cx + 3, cy + 3);
            int rgb = rarityColor(str(p, "tier"), null);
            String label = "[Lvl " + (int) num(p, "level") + "] " + ChatDisplay.safe(str(p, "name"), 30);
            g.text(font, ellipsize(label, cw - SLOT - 12), cx + SLOT + 7, cy + 4, rgb, false);
            String sub = flag(p, "active") ? "Active" + (has(p, "held") ? " · " + str(p, "held") : "") : has(p, "held") ? str(p, "held") : "";
            if (!sub.isEmpty()) small(g, ChatDisplay.safe(sub, 50), cx + SLOT + 7, cy + 15, flag(p, "active") ? ORANGE : FAINT);
            if (inContent && inside(mouseX, mouseY, cx, cy, cw, ch)) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(label).withStyle((s) -> s.withColor(rgb & 0xFFFFFF)));
                lines.add(Component.literal(String.valueOf(str(p, "tier")).replace("_", " ")).withStyle(ChatFormatting.GRAY));
                if (has(p, "held")) lines.add(Component.literal("Held: " + ChatDisplay.safe(str(p, "held"), 50)).withStyle(ChatFormatting.GRAY));
                hover.add(lines);
            }
        }
        return y + ((pets.size() + cols - 1) / cols) * (ch + gap);
    }

    private int drawDungeons(GuiGraphicsExtractor g, JsonObject dg, int x, int y, int w) {
        if (dg == null) return emptyCard(g, x, y, w, "No dungeons yet.");
        JsonObject cata = obj(dg, "catacombs");
        if (cata != null) {
            levelCard(g, cata, x, y, w, 30, vanilla("wither_skeleton_skull"), 0xFFC49BFF, "Catacombs");
            y += 34;
        }
        List<JsonObject> classes = list(dg, "classes");
        int cols = w >= 700 ? 5 : w >= 300 ? 3 : 2, gap = 4, cw = (w - gap * (cols - 1)) / cols;
        for (int i = 0; i < classes.size(); i++) {
            JsonObject c = classes.get(i);
            String key = String.valueOf(str(c, "key"));
            boolean selected = str(dg, "selectedClass") != null && str(dg, "selectedClass").equalsIgnoreCase(key);
            levelCard(g, c, x + (i % cols) * (cw + gap), y + (i / cols) * 34, cw, 30, vanilla(CLASS_ICON.getOrDefault(key, "book")),
                    0xFF000000 | CLASS_COLOR.getOrDefault(key, 0x5aa9ff), str(c, "name") + (selected ? " ★" : ""));
        }
        y += ((classes.size() + cols - 1) / cols) * 34 + 2;
        String[][] stats = {{"Class average", String.format(Locale.ROOT, "%.2f", num(dg, "classAverage"))}, {"Secrets", full(num(dg, "secrets"))},
                {"Total runs", full(num(dg, "runs"))}, {"Highest", has(dg, "highestMasterFloor") ? str(dg, "highestMasterFloor") : String.valueOf(str(dg, "highestFloor"))}};
        int sw = (w - gap * 3) / 4;
        for (int i = 0; i < 4; i++) {
            int sx = x + i * (sw + gap);
            card(g, sx, y, sw, 26, TILE);
            small(g, stats[i][0].toUpperCase(Locale.ROOT), sx + 6, y + 4, FAINT);
            g.text(font, ellipsize(stats[i][1], sw - 12), sx + 6, y + 13, TEXT, false);
        }
        y += 32;
        y = drawFloors(g, "Catacombs", list(dg, "floors"), x, y, w);
        return drawFloors(g, "Master Mode", list(dg, "masterFloors"), x, y, w);
    }

    private int drawFloors(GuiGraphicsExtractor g, String title, List<JsonObject> floors, int x, int y, int w) {
        if (floors.isEmpty()) return y;
        int h = 22 + floors.size() * 12 + 4;
        card(g, x, y, w, h, CARD);
        g.text(font, title, x + 8, y + 6, TEXT, false);
        int c2 = x + w / 2, c3 = x + w * 2 / 3, c4 = x + w * 5 / 6;
        small(g, "RUNS", c2, y + 8, FAINT);
        small(g, "BEST", c3, y + 8, FAINT);
        small(g, "FASTEST S", c4, y + 8, FAINT);
        for (int i = 0; i < floors.size(); i++) {
            JsonObject f = floors.get(i);
            int ry = y + 21 + i * 12;
            if (i % 2 == 0) g.fill(x + 4, ry - 2, x + w - 4, ry + 10, 0xFF181B22);
            g.text(font, ellipsize(ChatDisplay.safe(str(f, "name"), 30), c2 - x - 14), x + 8, ry, TEXT, false);
            g.text(font, full(num(f, "completions")), c2, ry, TEXT, false);
            g.text(font, full(num(f, "bestScore")), c3, ry, TEXT, false);
            double ms = num(f, "fastestS");
            g.text(font, ms > 0 ? String.format(Locale.ROOT, "%d:%02d", (long) (ms / 60000), (long) (ms / 1000) % 60) : "-", c4, ry, TEXT, false);
        }
        return y + h + 6;
    }

    private int drawSlayers(GuiGraphicsExtractor g, List<JsonObject> slayers, int x, int y, int w) {
        if (slayers.isEmpty()) return emptyCard(g, x, y, w, "No slayers yet.");
        int cols = w >= 360 ? 3 : 2, gap = 4, cw = (w - gap * (cols - 1)) / cols, ch = 40;
        for (int i = 0; i < slayers.size(); i++) {
            JsonObject s = slayers.get(i);
            int cx = x + (i % cols) * (cw + gap), cy = y + (i / cols) * (ch + gap);
            levelCard(g, s, cx, cy, cw, 30, vanilla(SLAYER_ICON.getOrDefault(String.valueOf(str(s, "key")), "rotten_flesh")), 0xFFFF7A7A, str(s, "name"));
            g.fill(cx, cy + 29, cx + cw, cy + ch, CARD_EDGE);
            g.fill(cx + 1, cy + 29, cx + cw - 1, cy + ch - 1, CARD);
            small(g, full(num(s, "kills")) + " bosses killed", cx + 23, cy + 31, FAINT);
        }
        return y + ((slayers.size() + cols - 1) / cols) * (ch + gap);
    }

    private int emptyCard(GuiGraphicsExtractor g, int x, int y, int w, String text) {
        card(g, x, y, w, 22, CARD);
        g.text(font, text, x + 8, y + 7, FAINT, false);
        return y + 22;
    }

    private List<Component> tooltip(Item it) {
        List<Component> lines = new ArrayList<>();
        lines.add(LegacyText.parse(it.name(), ChatFormatting.WHITE));
        for (String l : it.lore()) lines.add(LegacyText.parse(l, ChatFormatting.GRAY));
        if (it.source() != null) lines.add(Component.literal("Found in: " + it.source()).withStyle(ChatFormatting.DARK_GRAY));
        if (it.inactive()) lines.add(Component.literal("Inactive (a better one is active)").withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** An item slot: rarity-coloured frame with a tinted background, like the website. */
    private void slot(GuiGraphicsExtractor g, Item it, int x, int y) {
        int rgb = it == null ? 0xFF2B2F38 : rarityColor(it.rarity(), it.name());
        g.fill(x, y, x + SLOT, y + SLOT, rgb);
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xFF111318);
        if (it != null) g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, (rgb & 0x00FFFFFF) | 0x30000000);
        if (it == null) return;
        icon(g, it.image(), it.color(), x + 2, y + 2, 16);
        if (it.count() > 1) {
            String n = String.valueOf(it.count());
            g.text(font, n, x + SLOT - 1 - font.width(n), y + SLOT - 8, TEXT, true);
        }
    }

    private void petSlot(GuiGraphicsExtractor g, JsonObject pet, int x, int y) {
        int rgb = rarityColor(str(pet, "tier"), null);
        g.fill(x, y, x + SLOT, y + SLOT, rgb);
        g.fill(x + 1, y + 1, x + SLOT - 1, y + SLOT - 1, 0xE0111318);
        icon(g, str(pet, "image"), null, x + 2, y + 2, 16);
    }

    private void button(GuiGraphicsExtractor g, int x, int y, int w, String label, int mouseX, int mouseY, boolean enabled, boolean primary) {
        boolean hover = enabled && inside(mouseX, mouseY, x, y, w, 14);
        g.fill(x, y, x + w, y + 14, primary ? ORANGE : hover ? 0xFF3A3E49 : 0xFF23262E);
        g.centeredText(font, label, x + w / 2, y + 3, enabled ? TEXT : FAINT);
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, max));
    }

    // ── Input ───────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Only a click event that hit something stops the polled click below from also running:
        // some clients send click events with positions that don't line up with the screen.
        if (event.button() == 0 && click(event.x(), event.y())) {
            lastEventClick = System.currentTimeMillis();
            return true;
        }
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
        if (inside(mx, my, closeX(), py + 5, 40, 14)) {
            onClose();
            return true;
        }
        JsonObject d = data;
        if (d != null && inside(mx, my, websiteX(), py + 5, 52, 14)) {
            String url = str(d, "url");
            if (url != null && url.startsWith("https://skykings.net/")) ConfirmLinkScreen.confirmLinkNow(this, URI.create(url));
            return true;
        }
        if (my < top || my >= bottom) return false;
        for (int[] t : tabSpots) {
            if (inside(mx, my, t[0], t[1], t[2], t[3])) {
                tab = t[4];
                return true;
            }
        }
        if (d != null && !loading) {
            List<JsonObject> profiles = list(d, "profiles");
            for (int[] t : profileSpots) {
                if (inside(mx, my, t[0], t[1], t[2], t[3]) && t[4] < profiles.size() && !flag(profiles.get(t[4]), "selected")) {
                    fetch(str(profiles.get(t[4]), "name"));
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll -= vertical * 18;
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
