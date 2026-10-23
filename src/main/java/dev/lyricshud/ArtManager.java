package dev.lyricshud;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the album cover for the current song into a Minecraft texture.
 *
 * The "art token" comes from the media helper:
 *  - Windows: a unique id; the cover was written to config/lyricshud-art.img
 *  - Linux:   the cover URL from MPRIS (file:// or https://)
 * Covers can be JPEG, so we convert to a small PNG with ImageIO first (Minecraft only reads PNG).
 *
 * Every failure is logged (look for "lyricshud" in latest.log) instead of silently leaving the cover blank.
 */
public final class ArtManager {
	private static final int TEX_SIZE = 128;
	private static final int LOAD_ATTEMPTS = 3;

	private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "lyricshud-art");
		t.setDaemon(true);
		return t;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(6))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static volatile String requested = "";
	private static Identifier current;
	private static int texW = 1, texH = 1;
	private static int counter = 0;

	private ArtManager() {}

	/** Null until a cover has been loaded. Render thread only. */
	public static Identifier textureId() {
		return current;
	}

	public static int width() {
		return texW;
	}

	public static int height() {
		return texH;
	}

	/** Call every frame from the render thread with the current art token. */
	public static void tick(String token) {
		if (token == null) token = "";
		if (token.equals("preview") || token.equals(requested)) return;
		requested = token;
		if (token.isEmpty()) {
			release();
			return;
		}
		final String tk = token;
		LyricsHudClient.LOGGER.info("Album art: new cover requested ({})", abbreviate(tk));
		POOL.submit(() -> {
			byte[] png = null;
			Throwable failure = null;
			for (int attempt = 1; attempt <= LOAD_ATTEMPTS && png == null; attempt++) {
				try {
					png = loadPng(tk);
					if (png == null) failure = new IOException("cover was empty or the server didn't return 200");
				} catch (Throwable e) {
					failure = e;
				}
				if (png == null && attempt < LOAD_ATTEMPTS) sleep(250);
			}
			if (png == null) {
				LyricsHudClient.LOGGER.warn("Album art: couldn't load cover after {} tries: {}", LOAD_ATTEMPTS, String.valueOf(failure));
			}
			final byte[] data = png;
			MinecraftClient.getInstance().execute(() -> {
				if (!tk.equals(requested)) return; // song changed while loading
				if (data == null) {
					release();
					return;
				}
				try {
					install(data);
				} catch (Throwable e) {
					LyricsHudClient.LOGGER.warn("Album art: couldn't create the texture", e);
					release();
				}
			});
		});
	}

	private static byte[] loadPng(String token) throws Exception {
		byte[] raw;
		if (token.startsWith("http://") || token.startsWith("https://")) {
			HttpRequest req = HttpRequest.newBuilder(URI.create(token))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "LyricsHud-FabricMod/1.2")
				.GET().build();
			HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) return null;
			raw = res.body();
		} else if (token.startsWith("file:")) {
			raw = Files.readAllBytes(Path.of(URI.create(token)));
		} else {
			Path file = FabricLoader.getInstance().getConfigDir().resolve("lyricshud-art.img");
			raw = Files.readAllBytes(file);
		}
		if (raw == null || raw.length == 0) return null;

		BufferedImage src;
		try {
			src = ImageIO.read(new ByteArrayInputStream(raw));
		} catch (Throwable t) {
			throw new IOException("ImageIO is unavailable or failed: " + t, t);
		}
		if (src == null) {
			// Not a format ImageIO understands (e.g. WebP). Minecraft itself only reads PNG.
			if (isPng(raw)) return raw;
			throw new IOException("unsupported cover format (" + raw.length + " bytes, starts with " + hexStart(raw) + ")");
		}

		// centre-crop to a square (drawn into a fresh ARGB image so any source colour model works)
		int side = Math.min(src.getWidth(), src.getHeight());
		BufferedImage cur = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g0 = cur.createGraphics();
		g0.drawImage(src, -(src.getWidth() - side) / 2, -(src.getHeight() - side) / 2, null);
		g0.dispose();

		// halve repeatedly while still at least 2x too big (much cleaner than one huge jump), then final resize
		while (cur.getWidth() / 2 >= TEX_SIZE) {
			int n = cur.getWidth() / 2;
			cur = resize(cur, n);
		}
		cur = resize(cur, TEX_SIZE);

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		if (!ImageIO.write(cur, "png", bos)) throw new IOException("no PNG writer available");
		return bos.toByteArray();
	}

	private static BufferedImage resize(BufferedImage in, int size) {
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(in, 0, 0, size, size, null); // BufferedImage source: synchronous, unlike getScaledInstance
		g.dispose();
		return out;
	}

	private static void install(byte[] png) throws Exception {
		MinecraftClient mc = MinecraftClient.getInstance();
		NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
		Identifier id = Identifier.of(LyricsHudClient.MOD_ID, "art/" + (++counter));
		NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "lyricshud album art", img);
		mc.getTextureManager().registerTexture(id, tex);
		tex.upload();
		Identifier old = current;
		current = id;
		texW = img.getWidth();
		texH = img.getHeight();
		if (old != null) mc.getTextureManager().destroyTexture(old);
		LyricsHudClient.LOGGER.info("Album art: cover ready ({}x{})", texW, texH);
	}

	private static void release() {
		if (current != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(current);
			current = null;
		}
	}

	private static boolean isPng(byte[] b) {
		return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
	}

	private static String hexStart(byte[] b) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(8, b.length); i++) sb.append(String.format("%02X ", b[i]));
		return sb.toString().trim();
	}

	private static String abbreviate(String s) {
		return s.length() > 60 ? s.substring(0, 57) + "..." : s;
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}
}
