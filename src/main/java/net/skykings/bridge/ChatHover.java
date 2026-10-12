package net.skykings.bridge;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hovering an image or emoji in chat shows a small preview of it by the cursor.
 * The hovered chat text is read from the frame's drawing state, where Minecraft
 * keeps it for its own hover tooltips.
 */
public final class ChatHover {
    private ChatHover() {}

    private static final Pattern VIEW = Pattern.compile("^/?gbridge view (\\d{1,9})$");
    private static final int MAX_W = 160, MAX_H = 120;
    private static Field clickableField, hoveredField;
    private static boolean broken;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof ChatScreen) ScreenEvents.afterExtract(screen).register(ChatHover::draw);
        });
    }

    private static Style hovered(GuiGraphicsExtractor g) {
        if (broken) return null;
        try {
            if (clickableField == null) {
                clickableField = GuiGraphicsExtractor.class.getDeclaredField("clickableTextStyle");
                hoveredField = GuiGraphicsExtractor.class.getDeclaredField("hoveredTextStyle");
                clickableField.setAccessible(true);
                hoveredField.setAccessible(true);
            }
            Style s = (Style) clickableField.get(g);
            return s != null ? s : (Style) hoveredField.get(g);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            GuildBridgeMod.LOG.warn("Can't show image previews on this Minecraft version", e);
            return null;
        }
    }

    private static void draw(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Style style = hovered(g);
        if (style == null || !(style.getClickEvent() instanceof ClickEvent.RunCommand run)) return;
        Matcher m = VIEW.matcher(run.command());
        if (!m.matches()) return;
        MediaStore.Loaded image = MediaStore.open(Integer.parseInt(m.group(1)));
        if (image == null) return;

        Font font = screen.getFont();
        String caption = image.media.name();
        int w, h;
        if (image.texture != null) {
            double scale = Math.min(1.0, Math.min((double) MAX_W / image.width, (double) MAX_H / image.height));
            if (Math.max(image.width, image.height) < 48) scale = 48.0 / Math.max(image.width, image.height); // small emojis: bigger
            w = Math.max(1, (int) (image.width * scale));
            h = Math.max(1, (int) (image.height * scale));
        } else {
            w = Math.max(60, font.width(image.error != null ? image.error : "Loading...")); h = 10;
        }
        int boxW = Math.max(w, Math.min(MAX_W, font.width(caption))) + 8, boxH = h + 20;
        // Above and to the right of the cursor, kept on screen.
        int x = Math.min(mouseX + 8, screen.width - boxW - 2), y = mouseY - boxH - 6;
        if (y < 2) y = mouseY + 14;
        x = Math.max(2, x);

        g.nextStratum(); // above the chat text
        g.fill(x, y, x + boxW, y + boxH, 0xF0101116);
        g.fill(x, y, x + boxW, y + 1, 0xFF3A3E49);
        g.fill(x, y + boxH - 1, x + boxW, y + boxH, 0xFF3A3E49);
        g.fill(x, y, x + 1, y + boxH, 0xFF3A3E49);
        g.fill(x + boxW - 1, y, x + boxW, y + boxH, 0xFF3A3E49);
        g.text(font, ellipsize(font, caption, boxW - 8), x + 4, y + 4, 0xFFF2F3F5, false);
        int ix = x + (boxW - w) / 2, iy = y + 15;
        if (image.texture != null) {
            ImageScreen.blit(g, image.texture, ix, iy, w, h, image.width, image.height);
        } else {
            g.text(font, image.error != null ? image.error : "Loading...", x + 4, iy, image.error != null ? 0xFFF2545B : 0xFF9097A3, false);
        }
    }

    private static String ellipsize(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        while (!text.isEmpty() && font.width(text + "...") > width) text = text.substring(0, text.length() - 1);
        return text + "...";
    }
}
