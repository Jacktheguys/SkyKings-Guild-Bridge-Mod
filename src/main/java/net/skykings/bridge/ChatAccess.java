package net.skykings.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The chat window, on every supported Minecraft version (one jar for all).
 * 26.1 has it at gui.getChat(); 26.2+ moved it to gui.hud.getChat(). Minecraft
 * 26.x isn't obfuscated, so the right one is looked up by name once.
 */
final class ChatAccess {
    private ChatAccess() {}

    private static MethodHandle getChat; // (Gui) -> ChatComponent

    static ChatComponent chat(Minecraft minecraft) {
        try {
            if (getChat == null) getChat = find();
            return (ChatComponent) getChat.invoke(minecraft.gui);
        } catch (Throwable e) {
            throw new IllegalStateException("SkyKings Guild Bridge: can't find the chat on this Minecraft version", e);
        }
    }

    private static MethodHandle find() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            return lookup.findVirtual(Gui.class, "getChat", MethodType.methodType(ChatComponent.class)); // 26.1
        } catch (NoSuchMethodException newerVersion) {
            MethodHandle hud = lookup.unreflectGetter(Gui.class.getField("hud")); // 26.2+
            MethodHandle hudGetChat = lookup.findVirtual(hud.type().returnType(), "getChat", MethodType.methodType(ChatComponent.class));
            return MethodHandles.filterReturnValue(hud, hudGetChat);
        }
    }
}
