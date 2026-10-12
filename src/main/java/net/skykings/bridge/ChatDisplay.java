package net.skykings.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lines the mod shows in your chat. These only exist on your screen: nothing
 * here is sent to Hypixel. Text from other people is always added as plain
 * text, so it can't carry colours, links or clickable commands.
 */
public final class ChatDisplay {
    private ChatDisplay() {}

    /** Plain single-line text with colour codes and control characters removed. */
    public static String safe(String text, int maxLength) {
        String clean = text == null ? "" : text.replaceAll("§.?", "").replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    public static void show(Component text) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> ChatAccess.chat(minecraft).addClientSystemMessage(text));
    }

    private static MutableComponent tag(String label, ChatFormatting color) {
        return Component.literal("[" + label + "] ").withStyle(color);
    }

    /** A message from someone (Discord user or bridge chat). */
    public static void message(String label, ChatFormatting color, String author, String message) {
        message(label, color, author, null, message);
    }

    /** authorRgb: colour for the name (a player's chosen colour or a Discord role colour), or null for white. */
    public static void message(String label, ChatFormatting color, String author, Integer authorRgb, String message) {
        message(label, color, author, authorRgb, null, message);
    }

    /** textRgb: the message text's colour (the player's choice in /gbridge), or null for grey. */
    public static void message(String label, ChatFormatting color, String author, Integer authorRgb, Integer textRgb, String message) {
        message(label, color, author, authorRgb, textRgb, message, List.of(), List.of());
    }

    /** A picture in a message: a Discord custom emoji (shown as :name: in the text) or an image. */
    public record Media(String name, String url) {}

    /**
     * With Discord custom emojis and images: each :emoji: in the text and an
     * [image] tag per image can be clicked to see the picture in-game.
     */
    public static void message(String label, ChatFormatting color, String author, Integer authorRgb, Integer textRgb,
                               String message, List<Media> emojis, List<Media> images) {
        MutableComponent name = Component.literal(safe(author, 32));
        name = authorRgb == null ? name.withStyle(ChatFormatting.WHITE) : name.withStyle((s) -> s.withColor(authorRgb));
        Style textStyle = textRgb == null ? Style.EMPTY.withColor(ChatFormatting.GRAY) : Style.EMPTY.withColor(textRgb);
        MutableComponent line = tag(label, color).append(name).append(Component.literal(":").withStyle(textStyle));

        // Split the text at each ":name:" of a custom emoji in this message.
        Map<String, Integer> emojiIds = new HashMap<>();
        Map<String, Integer> emojiChars = new HashMap<>();
        for (Media e : emojis) {
            String key = ":" + safe(e.name(), 32) + ":";
            if (!emojiIds.containsKey(key)) {
                int id = MediaStore.add(key, e.url());
                if (id == 0) continue;
                emojiIds.put(key, id);
                int cp = EmojiFont.codepoint(e.url());
                if (cp >= 0) emojiChars.put(key, cp);
            }
        }
        String text = safe(message, 256);
        if (!text.isEmpty()) {
            line.append(Component.literal(" ").withStyle(textStyle));
            StringBuilder plain = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                String hit = null;
                if (text.charAt(i) == ':') {
                    for (String key : emojiIds.keySet()) if (text.startsWith(key, i)) { hit = key; break; }
                }
                if (hit == null) { plain.append(text.charAt(i++)); continue; }
                if (!plain.isEmpty()) { line.append(Component.literal(plain.toString()).withStyle(textStyle)); plain.setLength(0); }
                Integer cp = emojiChars.get(hit);
                if (cp != null) {
                    // The emoji itself, drawn by the mod's emoji font.
                    line.append(Component.literal(new String(Character.toChars(cp)))
                            .withStyle(EmojiFont.style(viewStyle(emojiIds.get(hit)).withColor(ChatFormatting.WHITE))));
                } else {
                    line.append(Component.literal(hit).withStyle(viewStyle(emojiIds.get(hit))));
                }
                i += hit.length();
            }
            if (!plain.isEmpty()) line.append(Component.literal(plain.toString()).withStyle(textStyle));
        }
        for (Media img : images) {
            int id = MediaStore.add(img.name(), img.url());
            if (id == 0) continue;
            line.append(Component.literal(" "));
            line.append(Component.literal("[" + safe(img.name(), 40) + "]").withStyle(viewStyle(id)));
        }
        show(line);
    }

    /**
     * Clicking runs "/gbridge view <id>", which this mod handles itself: it's never sent to
     * Hypixel. Hovering shows a preview (ChatHover), so there's no hover text.
     */
    private static Style viewStyle(int id) {
        return Style.EMPTY.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand("gbridge view " + id));
    }

    public static final String PREMIUM_URL = "https://skykings.net/premium";

    /** "Custom colours are for patrons" with a link to the premium page (the game asks before opening it). */
    public static void premiumNotice() {
        show(tag("Bridge", ChatFormatting.DARK_AQUA)
                .append(Component.literal("Custom hex colours are for SkyKings patrons. ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("[Get Premium]").withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withBold(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(PREMIUM_URL)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(PREMIUM_URL))))));
    }

    /** A message from the mod itself. */
    public static void system(String message) {
        show(tag("Bridge", ChatFormatting.DARK_AQUA).append(Component.literal(safe(message, 300)).withStyle(ChatFormatting.GRAY)));
    }

    /** Clicking puts `command` in the chat box; the player still has to press Enter. */
    public static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY
                .withColor(color)
                .withBold(true)
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
