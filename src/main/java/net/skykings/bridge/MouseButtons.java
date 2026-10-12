package net.skykings.bridge;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;

/**
 * Whether the left mouse button is held, read straight from the window
 * library: SDL on Minecraft 26.3, GLFW on 26.1 and 26.2. Used by the settings
 * screen when a client (e.g. Lunar) doesn't pass clicks on to mod screens.
 * Looked up by name, since each version ships only one of the two; if
 * anything goes wrong it switches itself off (normal clicks still work).
 */
final class MouseButtons {
    private MouseButtons() {}

    private static boolean ready;
    private static boolean broken;
    private static Method sdlGetMouseState; // SDLMouse.nSDL_GetMouseState(long x, long y) -> button flags
    private static Method glfwGetMouseButton; // GLFW.glfwGetMouseButton(long window, int button) -> 1 when pressed

    /** true/false, or null if it can't be read on this setup. */
    static Boolean leftDown() {
        if (broken) return null;
        try {
            if (!ready) find();
            if (sdlGetMouseState != null) {
                int flags = (int) sdlGetMouseState.invoke(null, 0L, 0L); // null pointers: we only want the buttons
                return (flags & 1) != 0; // SDL_BUTTON_LMASK
            }
            long window = Minecraft.getInstance().getWindow().handle();
            return (int) glfwGetMouseButton.invoke(null, window, 0) == 1; // GLFW_MOUSE_BUTTON_LEFT, GLFW_PRESS
        } catch (Throwable e) {
            broken = true;
            GuildBridgeMod.LOG.warn("Can't read the mouse button directly; using normal clicks only", e);
            return null;
        }
    }

    private static void find() throws ReflectiveOperationException {
        ready = true;
        try {
            // SDL first: on 26.3 the window is an SDL window (GLFW, if present at all, isn't set up).
            sdlGetMouseState = Class.forName("org.lwjgl.sdl.SDLMouse").getMethod("nSDL_GetMouseState", long.class, long.class);
            GuildBridgeMod.LOG.info("Settings screen: reading the mouse button through SDL");
        } catch (ClassNotFoundException noSdl) {
            glfwGetMouseButton = Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetMouseButton", long.class, int.class);
            GuildBridgeMod.LOG.info("Settings screen: reading the mouse button through GLFW");
        }
    }
}
