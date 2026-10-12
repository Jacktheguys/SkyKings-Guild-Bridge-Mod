package net.skykings.bridge;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Images and custom emojis from Discord messages. Each one gets a number for
 * "/gbridge view <n>" (the clickable part of the chat line).
 *
 * Safety: pictures are only downloaded from Discord's own image servers
 * (https, no redirects), are never saved to disk or run, and are only decoded
 * as images in memory. The size and pixel dimensions are checked before
 * decoding, so a booby-trapped file can't make the game run out of memory.
 * Anything that isn't an image is never downloaded at all.
 */
public final class MediaStore {
    private MediaStore() {}

    private static final Set<String> HOSTS = Set.of("cdn.discordapp.com", "media.discordapp.net");
    private static final List<String> PATHS = List.of("/attachments/", "/ephemeral-attachments/", "/emojis/", "/stickers/");
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SIDE = 2048;
    private static final int KEEP_ENTRIES = 300;  // clickable media remembered
    private static final int KEEP_TEXTURES = 8;   // pictures kept loaded
    private static final long RETRY_AFTER_MS = 15_000;

    // Item pictures for /gpv come from the SkyKings website's own image routes, nothing else there.
    public static final String SITE = "https://skykings.net";
    private static final List<String> SITE_PATHS = List.of("/minecraft-item-image/", "/skyblock-head/", "/images/minecraft/", "/mc/mh/body/");
    private static final int KEEP_ICONS = 300;

    public record Media(int id, String name, String url) {}

    /** A picture being opened: loading, loaded (texture set) or failed (error set). */
    public static final class Loaded {
        public final Media media;
        public volatile Identifier texture;
        public volatile int width, height;
        public volatile String error;
        volatile long failedAt;
        Loaded(Media media) { this.media = media; }
    }

    private static final Map<Integer, Media> ENTRIES = new LinkedHashMap<>();
    private static final Map<Integer, Loaded> LOADED = new LinkedHashMap<>();
    private static final Deque<Integer> TEXTURE_ORDER = new ArrayDeque<>();
    private static int nextId = 1;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /**
     * https on a Discord image host and path, nothing else. Uploaded files must go
     * through Discord's image proxy as a PNG (media.discordapp.net, format=png), so
     * what reaches the game is a picture Discord re-made, never the uploader's file.
     */
    public static boolean allowed(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath(), host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean basic = "https".equals(uri.getScheme()) && HOSTS.contains(host)
                    && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && path != null && safePath(uri) && PATHS.stream().anyMatch(path::startsWith);
            if (!basic) return false;
            if (path.startsWith("/attachments/") || path.startsWith("/ephemeral-attachments/")) {
                String query = "&" + (uri.getRawQuery() == null ? "" : uri.getRawQuery()) + "&";
                return host.equals("media.discordapp.net") && query.contains("&format=png&");
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** An item picture on the SkyKings website (https, skykings.net, image routes only). */
    public static boolean allowedSite(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            return "https".equals(uri.getScheme()) && "skykings.net".equalsIgnoreCase(uri.getHost()) && uri.getPort() == -1
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && path != null && safePath(uri) && SITE_PATHS.stream().anyMatch(path::startsWith);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final Map<String, Loaded> ICONS = new LinkedHashMap<>(64, 0.75f, true); // most recently used last
    private static int nextIcon = 1;

    /**
     * An item picture for the /gpv screen (path on skykings.net, e.g. "/minecraft-item-image/HYPERION"),
     * downloaded once and kept while it's in use. tint: dyed leather colour (RGB), or null.
     */
    public static synchronized Loaded icon(String path, Integer tint) {
        if (path == null) return null;
        String url = SITE + path;
        String key = url + "|" + tint;
        Loaded existing = ICONS.get(key);
        if (existing != null && (existing.error == null || System.currentTimeMillis() - existing.failedAt < RETRY_AFTER_MS * 4)) return existing;
        Loaded loading = new Loaded(new Media(-(nextIcon++), "icon", url));
        ICONS.put(key, loading);
        download(url, 512 * 1024, 512).whenComplete((image, error) -> {
            if (error != null) {
                loading.error = "x";
                loading.failedAt = System.currentTimeMillis();
                return;
            }
            if (tint != null) tintImage(image, tint);
            Minecraft.getInstance().execute(() -> uploadIcon(loading, image));
        });
        while (ICONS.size() > KEEP_ICONS) {
            Loaded old = ICONS.remove(ICONS.keySet().iterator().next());
            if (old.texture != null) Minecraft.getInstance().getTextureManager().release(old.texture);
        }
        return loading;
    }

    private static synchronized void uploadIcon(Loaded loading, NativeImage image) {
        Identifier texture = Identifier.fromNamespaceAndPath("skykings_bridge", "icon/" + (-loading.media.id()));
        Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "skykings_bridge icon", image));
        loading.width = image.getWidth();
        loading.height = image.getHeight();
        loading.texture = texture;
        if (!ICONS.containsValue(loading)) Minecraft.getInstance().getTextureManager().release(texture); // evicted while loading
    }

    /** Dyed leather: the website's leather pictures are grey, coloured by multiplying. */
    private static void tintImage(NativeImage image, int rgb) {
        int tr = (rgb >> 16) & 0xFF, tg = (rgb >> 8) & 0xFF, tb = rgb & 0xFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getPixel(x, y);
                int r = ((p >> 16) & 0xFF) * tr / 255, g = ((p >> 8) & 0xFF) * tg / 255, b = (p & 0xFF) * tb / 255;
                image.setPixel(x, y, (p & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
    }

    /** No ".." or backslashes, also once decoded (so "%2e%2e" can't sneak out of the allowed folders). */
    private static boolean safePath(URI uri) {
        String raw = uri.getRawPath(), decoded = uri.getPath();
        return raw != null && decoded != null && !raw.contains("..") && !decoded.contains("..") && !decoded.contains("\\");
    }

    /** Remembers a picture; returns its number, or 0 if the address isn't allowed. */
    public static synchronized int add(String name, String url) {
        if (url == null || !allowed(url)) return 0;
        int id = nextId++;
        ENTRIES.put(id, new Media(id, ChatDisplay.safe(name, 64), url));
        while (ENTRIES.size() > KEEP_ENTRIES) ENTRIES.remove(ENTRIES.keySet().iterator().next());
        return id;
    }

    public static synchronized Media get(int id) {
        return ENTRIES.get(id);
    }

    /** Starts downloading (once) and returns the state, or null for an unknown number. */
    public static synchronized Loaded open(int id) {
        Loaded existing = LOADED.get(id);
        if (existing != null && (existing.error == null || System.currentTimeMillis() - existing.failedAt < RETRY_AFTER_MS)) return existing;
        Media media = ENTRIES.get(id);
        if (media == null) return null;
        Loaded loading = new Loaded(media);
        LOADED.put(id, loading);
        download(media.url(), MAX_BYTES, MAX_SIDE).whenComplete((image, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null ? error.getCause() : error;
                loading.error = cause instanceof IOException && cause.getMessage() != null ? cause.getMessage() : "Couldn't download it.";
                loading.failedAt = System.currentTimeMillis();
                return;
            }
            Minecraft.getInstance().execute(() -> upload(id, loading, image));
        });
        return loading;
    }

    /**
     * Downloads and decodes an image from an allowed address. Fails (IOException with a
     * readable message) for other addresses, big files, huge dimensions or non-images.
     */
    public static CompletableFuture<NativeImage> download(String url, int maxBytes, int maxSide) {
        return download(url, maxBytes, maxSide, 3);
    }

    // Redirects are followed by hand, and only to another allowed address (pet pictures
    // on the website redirect to a head picture there).
    private static CompletableFuture<NativeImage> download(String url, int maxBytes, int maxSide, int redirectsLeft) {
        if (!allowed(url) && !allowedSite(url)) return CompletableFuture.failedFuture(new IOException("That address isn't allowed."));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SkyKings-Guild-Bridge")
                .header("Accept", "image/png,image/jpeg;q=0.9,image/gif;q=0.8") // no WebP: the game can't decode it
                .GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).thenCompose((response) -> {
            int status = response.statusCode();
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && redirectsLeft > 0) {
                try (InputStream ignored = response.body()) {
                    String next = response.headers().firstValue("location").map((l) -> URI.create(url).resolve(l).toString()).orElse("");
                    return download(next, maxBytes, maxSide, redirectsLeft - 1);
                } catch (IOException | IllegalArgumentException e) {
                    return CompletableFuture.failedFuture(new IOException("Bad redirect."));
                }
            }
            return CompletableFuture.completedFuture(readImage(response, maxBytes, maxSide));
        });
    }

    private static NativeImage readImage(HttpResponse<InputStream> response, int maxBytes, int maxSide) {
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("The server said " + response.statusCode() + " (the link may have expired).");
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1);
            if (declared > maxBytes) throw new IOException("It's too big to show.");
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("It's too big to show.");
            NativeImage image = decode(bytes, maxSide);
            if (image == null) throw new IOException("That image can't be shown.");
            return image;
        } catch (IOException e) {
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    private static synchronized void upload(int id, Loaded loading, NativeImage image) {
        Identifier texture = Identifier.fromNamespaceAndPath("skykings_bridge", "media/" + id);
        Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "skykings_bridge media " + id, image));
        loading.width = image.getWidth();
        loading.height = image.getHeight();
        loading.texture = texture;
        TEXTURE_ORDER.addLast(id);
        while (TEXTURE_ORDER.size() > KEEP_TEXTURES) {
            int old = TEXTURE_ORDER.removeFirst();
            LOADED.remove(old);
            Minecraft.getInstance().getTextureManager().release(Identifier.fromNamespaceAndPath("skykings_bridge", "media/" + old));
        }
    }

    /**
     * PNG through Minecraft; JPEG/GIF/BMP through Java's ImageIO when the game's Java
     * has it. The width and height are read from the file header first, and huge
     * images are refused before any pixels are decoded.
     */
    static NativeImage decode(byte[] bytes, int maxSide) {
        if (isPng(bytes)) {
            int w = readInt(bytes, 16), h = readInt(bytes, 20); // IHDR is always the first chunk
            if (w <= 0 || h <= 0 || w > maxSide || h > maxSide) return null;
            try {
                return NativeImage.read(bytes);
            } catch (Exception e) {
                return null;
            }
        }
        try (javax.imageio.stream.ImageInputStream in = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return null;
            var readers = javax.imageio.ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            javax.imageio.ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase();
                if (!Set.of("jpeg", "gif", "bmp").contains(format)) return null;
                reader.setInput(in, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > maxSide || h > maxSide) return null;
                java.awt.image.BufferedImage img = reader.read(0);
                NativeImage image = new NativeImage(w, h, false);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) image.setPixel(x, y, img.getRGB(x, y)); // both ARGB
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (Throwable e) { // includes a Java without java.desktop
            return null;
        }
    }

    private static boolean isPng(byte[] b) {
        return b.length > 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R';
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }
}
