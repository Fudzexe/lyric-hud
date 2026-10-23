package dev.lyricshud;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
 *  - Windows: a counter; the cover was written to config/lyricshud-art.img
 *  - Linux:   the cover URL from MPRIS (file:// or https://)
 * Covers can be JPEG, so we convert to a small PNG with ImageIO first (Minecraft only reads PNG).
 */
public final class ArtManager {
	private static final int TEX_SIZE = 128;

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
		POOL.submit(() -> {
			byte[] png = null;
			try {
				png = loadPng(tk);
			} catch (Throwable e) {
				LyricsHudClient.LOGGER.warn("Couldn't load album art: {}", e.toString());
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
					LyricsHudClient.LOGGER.warn("Couldn't create album art texture: {}", e.toString());
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
			raw = Files.readAllBytes(FabricLoader.getInstance().getConfigDir().resolve("lyricshud-art.img"));
		}
		if (raw == null || raw.length == 0) return null;

		try {
			BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
			if (src == null) return raw;
			int side = Math.min(src.getWidth(), src.getHeight());
			BufferedImage square = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
			Image scaled = square.getScaledInstance(TEX_SIZE, TEX_SIZE, Image.SCALE_AREA_AVERAGING);
			BufferedImage out = new BufferedImage(TEX_SIZE, TEX_SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = out.createGraphics();
			g.drawImage(scaled, 0, 0, null);
			g.dispose();
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ImageIO.write(out, "png", bos);
			return bos.toByteArray();
		} catch (Throwable t) {
			return raw; // no ImageIO available: works if the cover is already a PNG
		}
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
	}

	private static void release() {
		if (current != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(current);
			current = null;
		}
	}
}
