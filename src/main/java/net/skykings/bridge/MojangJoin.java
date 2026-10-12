package net.skykings.bridge;

import net.minecraft.client.Minecraft;
import net.minecraft.server.Services;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Tells Mojang "this account is joining <serverId>", on every supported
 * Minecraft version (one jar for all). Mojang's login library renamed the
 * interface between versions (MinecraftSessionService in 26.1/26.2,
 * SessionService in 26.3), so the method is found by name instead of being
 * compiled against one of them. The login token goes only to Mojang, through
 * Minecraft's own code.
 */
final class MojangJoin {
    private MojangJoin() {}

    private static Method sessionService; // Services.sessionService()
    private static Method joinServer;     // <SessionService>.joinServer(UUID, String, String)

    static void joinServer(Minecraft minecraft, UUID profileId, String accessToken, String serverId) throws Exception {
        if (joinServer == null) {
            sessionService = Services.class.getMethod("sessionService");
            joinServer = sessionService.getReturnType().getMethod("joinServer", UUID.class, String.class, String.class);
        }
        Object service = sessionService.invoke(minecraft.services());
        try {
            joinServer.invoke(service, profileId, accessToken, serverId);
        } catch (InvocationTargetException e) {
            // e.g. Mojang said no: pass on the real error, not the reflection wrapper
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
