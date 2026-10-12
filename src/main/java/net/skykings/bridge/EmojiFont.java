package net.skykings.bridge;

import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discord custom emojis drawn inside chat lines. The mod adds its own small font
 * ("skykings_bridge:emoji") where each emoji is one character: a chat line shows
 * the emoji by using that character in that font. The character is blank until
 * the picture has downloaded (from Discord's emoji server only), then it turns
 * into the emoji.
 *
 * Minecraft has no public way to add a font while the game runs, so the font is
 * added to the font manager by name. If any of that is missing on some version,
 * emojis are shown as :name: text instead.
 */
public final class EmojiFont {
    private EmojiFont() {}

    private static final Identifier FONT_ID = Identifier.fromNamespaceAndPath("skykings_bridge", "emoji");
    private static final int FIRST = 0xF0000;   // a private-use plane: only means something in this font
    private static final int MAX_EMOJIS = 1000;
    private static final int PIXELS = 32;       // emojis are shrunk to this size for the font
    private static final float HEIGHT = 9f;     // drawn size in chat (a line is 9 high)

    private static boolean broken;
    private static Constructor<?> glyphConstructor;  // BitmapProvider.Glyph(scale, image or ImageDataHolder, x, y, w, h, advance, ascent)
    private static Constructor<?> holderConstructor; // 26.2+: BitmapProvider.ImageDataHolder(Identifier, NativeImage)
    private static Field fontManagerField, fontSetsField;
    private static Field[] cacheFields;
    private static Method invalidate;

    private static final Map<String, Integer> BY_URL = new HashMap<>();
    private static final Map<Integer, Object> GLYPHS = new HashMap<>(); // codepoint -> UnbakedGlyph
    private static int next = FIRST;
    private static Object placeholder;
    private static FontSet fontSet;
    private static boolean dirty;

    /** The style for an emoji character (no shadow: it would show as a dark square behind it). */
    public static Style style(Style base) {
        return base.withFont(new FontDescription.Resource(FONT_ID)).withoutShadow();
    }

    /**
     * The character for this emoji (downloading it if it's new), or -1 to show :name: as text.
     * Any thread.
     */
    public static synchronized int codepoint(String url) {
        if (broken || fontSet == null || !url.contains("/emojis/") || !MediaStore.allowed(url)) return -1;
        Integer known = BY_URL.get(url);
        if (known != null) return known;
        if (next - FIRST >= MAX_EMOJIS) return -1;
        int cp = next++;
        BY_URL.put(url, cp);
        MediaStore.download(url, 1024 * 1024, 512).whenComplete((image, error) -> {
            if (error != null) return; // stays blank; clicking it still tries the viewer
            Minecraft.getInstance().execute(() -> addGlyph(cp, image));
        });
        return cp;
    }

    /** Called every client tick: keeps the font registered (a resource reload removes it). */
    public static void tick() {
        if (broken) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!lookUp(mc)) return;
            @SuppressWarnings("unchecked")
            Map<Identifier, FontSet> sets = (Map<Identifier, FontSet>) fontSetsField.get(fontManagerField.get(mc));
            if (fontSet == null || sets.get(FONT_ID) != fontSet) {
                fontSet = new FontSet(new GlyphStitcher(mc.getTextureManager(), Identifier.fromNamespaceAndPath("skykings_bridge", "emoji_glyphs")));
                fontSet.reload(List.of(new GlyphProvider.Conditional(new Provider(), FontOption.Filter.ALWAYS_PASS)), Set.of());
                sets.put(FONT_ID, fontSet);
                dirty = false;
                invalidateCaches(mc);
            } else if (dirty) {
                dirty = false;
                fontSet.reload(List.of(new GlyphProvider.Conditional(new Provider(), FontOption.Filter.ALWAYS_PASS)), Set.of());
                invalidateCaches(mc);
            }
        } catch (Throwable e) {
            fail(e);
        }
    }

    private static void invalidateCaches(Minecraft mc) throws ReflectiveOperationException {
        Object manager = fontManagerField.get(mc);
        for (Field f : cacheFields) invalidate.invoke(f.get(manager));
    }

    private static boolean lookUp(Minecraft mc) throws ReflectiveOperationException {
        if (glyphConstructor != null) return true;
        for (Field f : Minecraft.class.getDeclaredFields()) {
            if (f.getType().getName().equals("net.minecraft.client.gui.font.FontManager")) fontManagerField = f;
        }
        if (fontManagerField == null) throw new NoSuchFieldException("Minecraft.fontManager");
        fontManagerField.setAccessible(true);
        Class<?> manager = fontManagerField.getType();
        fontSetsField = manager.getDeclaredField("fontSets");
        fontSetsField.setAccessible(true);
        Field any = manager.getDeclaredField("anyGlyphs"), nonFishy = manager.getDeclaredField("nonFishyGlyphs");
        any.setAccessible(true);
        nonFishy.setAccessible(true);
        cacheFields = new Field[]{any, nonFishy};
        invalidate = any.getType().getDeclaredMethod("invalidate");
        invalidate.setAccessible(true);

        Class<?> glyph = Class.forName("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph");
        for (Constructor<?> c : glyph.getDeclaredConstructors()) {
            if (c.getParameterCount() == 8) glyphConstructor = c;
        }
        if (glyphConstructor == null) throw new NoSuchMethodException("BitmapProvider.Glyph");
        glyphConstructor.setAccessible(true);
        Class<?> data = glyphConstructor.getParameterTypes()[1];
        if (data != NativeImage.class) {
            holderConstructor = data.getDeclaredConstructor(Identifier.class, NativeImage.class);
            holderConstructor.setAccessible(true);
        }
        NativeImage blank = new NativeImage(PIXELS, PIXELS, true); // all transparent
        placeholder = makeGlyph(-1, blank);
        return true;
    }

    private static Object makeGlyph(int cp, NativeImage image) throws ReflectiveOperationException {
        int w = image.getWidth(), h = image.getHeight();
        float scale = HEIGHT / Math.max(w, h);
        int drawnH = Math.round(h * scale);
        int ascent = 8 - (9 - drawnH) / 2; // centred on the line
        int advance = Math.round(w * scale) + 1;
        Object data = holderConstructor == null ? image
                : holderConstructor.newInstance(Identifier.fromNamespaceAndPath("skykings_bridge", "emoji/" + (cp < 0 ? "blank" : Integer.toHexString(cp))), image);
        return glyphConstructor.newInstance(scale, data, 0, 0, w, h, advance, ascent);
    }

    private static synchronized void addGlyph(int cp, NativeImage downloaded) {
        try {
            if (broken || !lookUp(Minecraft.getInstance())) { downloaded.close(); return; }
            NativeImage image = shrink(downloaded);
            GLYPHS.put(cp, makeGlyph(cp, image));
            dirty = true;
        } catch (Throwable e) {
            downloaded.close();
            fail(e);
        }
    }

    /** Box-filters the picture down to PIXELS on its longest side (font glyphs aren't smoothed). */
    private static NativeImage shrink(NativeImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= PIXELS) return src;
        int nw = Math.max(1, Math.round((float) w * PIXELS / longest)), nh = Math.max(1, Math.round((float) h * PIXELS / longest));
        NativeImage out = new NativeImage(nw, nh, true);
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int x0 = x * w / nw, x1 = Math.max(x0 + 1, (x + 1) * w / nw);
                int y0 = y * h / nh, y1 = Math.max(y0 + 1, (y + 1) * h / nh);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int p = src.getPixel(sx, sy); // ARGB
                        int pa = p >>> 24;
                        a += pa;
                        r += (long) ((p >> 16) & 0xFF) * pa;
                        g += (long) ((p >> 8) & 0xFF) * pa;
                        b += (long) (p & 0xFF) * pa;
                        n++;
                    }
                }
                int argb = a == 0 ? 0 : (int) (a / n) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                out.setPixel(x, y, argb);
            }
        }
        src.close();
        return out;
    }

    private static void fail(Throwable e) {
        if (!broken) GuildBridgeMod.LOG.warn("Can't draw Discord emojis in chat on this Minecraft version; showing :name: instead", e);
        broken = true;
    }

    private static final class Provider implements GlyphProvider {
        @Override
        public UnbakedGlyph getGlyph(int cp) {
            synchronized (EmojiFont.class) {
                Object glyph = GLYPHS.get(cp);
                if (glyph == null && cp >= FIRST && cp < next) glyph = placeholder; // still downloading
                return (UnbakedGlyph) glyph;
            }
        }

        @Override
        public IntSet getSupportedGlyphs() {
            synchronized (EmojiFont.class) {
                IntSet set = new IntOpenHashSet();
                for (int cp = FIRST; cp < next; cp++) set.add(cp);
                return set;
            }
        }

        @Override
        public void close() {
            // the glyph images are reused by the next copy of the font
        }
    }
}
