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

    private static boolean textInputMissing;

    /**
     * Turns typed-text delivery on or off for one of our screens. 26.3 reads the keyboard
     * through SDL, which only sends typed characters (charTyped) while something has asked
     * for text input, as vanilla text boxes do when focused; our boxes are drawn by hand,
     * so they ask here. 26.1 and 26.2 always send typed characters and have no such call.
     */
    static void textInput(Object owner, boolean on) {
        if (textInputMissing) return;
        try {
            Object manager = Minecraft.class.getMethod("textInputManager").invoke(Minecraft.getInstance());
            manager.getClass().getMethod(on ? "startTextInput" : "stopTextInput", Object.class).invoke(manager, owner);
        } catch (NoSuchMethodException olderVersion) {
            textInputMissing = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            textInputMissing = true;
            GuildBridgeMod.LOG.warn("Couldn't turn on text input for typing in the bridge screens", e);
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
