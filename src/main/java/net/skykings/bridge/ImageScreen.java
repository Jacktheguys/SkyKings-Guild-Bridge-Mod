package net.skykings.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Shows one image or emoji from Discord ("/gbridge view <n>"). Click or Esc closes it. */
public final class ImageScreen extends Screen {
    private static final int TEXT = 0xFFF2F3F5;
    private static final int MUTED = 0xFF9097A3;

    private final MediaStore.Loaded image;
    private boolean buttonWasDown = true; // the click that opened this screen may still be held

    public ImageScreen(MediaStore.Loaded image) {
        super(Component.literal(image.media.name()));
        this.image = image;
    }

    @Override
    protected void init() {
        super.init();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.mouseHandler.isMouseGrabbed()) minecraft.mouseHandler.releaseMouse();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        // Lunar Client doesn't pass clicks to mod screens, so a fresh press of the left button also closes it.
        Boolean held = MouseButtons.leftDown();
        if (held != null) {
            if (held && !buttonWasDown) Minecraft.getInstance().execute(this::onClose);
            buttonWasDown = held;
        }
        g.fill(0, 0, width, height, 0xC0000000);
        g.centeredText(font, image.media.name(), width / 2, 10, TEXT);
        g.centeredText(font, "Click or press Esc to close", width / 2, height - 16, MUTED);

        if (image.texture == null) {
            g.centeredText(font, image.error != null ? image.error : "Loading...", width / 2, height / 2 - 4, image.error != null ? 0xFFF2545B : MUTED);
            return;
        }
        // Fit inside the screen (with room for the text); small emojis are scaled up to at least 64px.
        int maxW = width - 40, maxH = height - 56;
        double scale = Math.min((double) maxW / image.width, (double) maxH / image.height);
        if (image.width * scale > image.width && Math.max(image.width, image.height) >= 64) scale = Math.min(scale, 1.0);
        int w = Math.max(1, (int) (image.width * scale)), h = Math.max(1, (int) (image.height * scale));
        int x = (width - w) / 2, y = 26 + (maxH - h) / 2;
        if (!blit(g, image.texture, x, y, w, h, image.width, image.height)) {
            g.centeredText(font, "This Minecraft version can't show images yet.", width / 2, height / 2 - 4, MUTED);
        }
    }

    // RenderPipeline moved packages in 26.3 (com.mojang.blaze3d.pipeline -> com.mojang.renderpearl.api.pipeline),
    // so the textured blit and RenderPipelines.GUI_TEXTURED are looked up by name.
    private static Method blitMethod;
    private static Object guiTextured;
    private static boolean lookedUp;

    static boolean blit(GuiGraphicsExtractor g, Identifier texture, int x, int y, int w, int h, int texW, int texH) {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Field field = Class.forName("net.minecraft.client.renderer.RenderPipelines").getField("GUI_TEXTURED");
                guiTextured = field.get(null);
                for (Method m : GuiGraphicsExtractor.class.getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("blit") && p.length == 12 && p[0] == field.getType() && p[1] == Identifier.class
                            && p[4] == float.class && p[11] == int.class) {
                        blitMethod = m;
                        break;
                    }
                }
            } catch (ReflectiveOperationException e) {
                GuildBridgeMod.LOG.warn("Can't draw images on this Minecraft version", e);
            }
        }
        if (blitMethod == null || guiTextured == null) return false;
        try {
            blitMethod.invoke(g, guiTextured, texture, x, y, 0f, 0f, w, h, texW, texH, texW, texH);
            return true;
        } catch (ReflectiveOperationException e) {
            blitMethod = null;
            GuildBridgeMod.LOG.warn("Drawing an image failed", e);
            return false;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        onClose();
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
