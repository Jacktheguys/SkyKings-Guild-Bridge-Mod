package net.skykings.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Opening and closing screens on every supported Minecraft version (one jar
 * for all). 26.1 has minecraft.setScreen(screen); 26.3 moved it to
 * minecraft.gui.setScreen(screen). Looked up by name once (26.x isn't
 * obfuscated). null closes the current screen.
 */
final class ScreenAccess {
    private ScreenAccess() {}

    private static MethodHandle setScreen; // (Minecraft, Screen) -> void

    static void setScreen(Minecraft minecraft, Screen screen) {
        try {
            if (setScreen == null) setScreen = find();
            setScreen.invoke(minecraft, screen);
        } catch (Throwable e) {
            throw new IllegalStateException("SkyKings Guild Bridge: can't open screens on this Minecraft version", e);
        }
    }

    private static MethodHandle find() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodType type = MethodType.methodType(void.class, Screen.class);
        try {
            return lookup.findVirtual(Minecraft.class, "setScreen", type); // 26.1
        } catch (NoSuchMethodException newerVersion) {
            MethodHandle gui = lookup.findGetter(Minecraft.class, "gui", Gui.class); // 26.3
            MethodHandle guiSetScreen = lookup.findVirtual(Gui.class, "setScreen", type);
            return MethodHandles.filterArguments(guiSetScreen, 0, gui);
        }
    }
}
