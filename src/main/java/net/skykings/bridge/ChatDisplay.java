package net.skykings.bridge;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

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
        MutableComponent name = Component.literal(safe(author, 32));
        name = authorRgb == null ? name.withStyle(ChatFormatting.WHITE) : name.withStyle((s) -> s.withColor(authorRgb));
        show(tag(label, color)
                .append(name)
                .append(Component.literal(": " + safe(message, 256)).withStyle(ChatFormatting.GRAY)));
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
