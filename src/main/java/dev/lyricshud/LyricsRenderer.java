package dev.lyricshud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** Draws the lyrics panel. Shared by the in-game HUD and the drag/colour editor screen. */
public final class LyricsRenderer {
	public record Box(int x, int y, int w, int h) {
		public boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record Row(OrderedText text, int argb, int y) {}

	private static final int PAD = 6;
	private static final int LINE_H = 9;
	private static final int GAP = 3;

	private static final int DIM_TEXT = 0x8A8A94;

	private LyricsRenderer() {}

	/** Fake song used by the editor when nothing is actually playing. */
	public static MediaState.Snapshot preview() {
		List<MediaState.Line> lines = List.of(
			new MediaState.Line(0, "Do you think about the days when we sat down"),
			new MediaState.Line(4000, "Smoking, wine and drinking haze? Or"),
			new MediaState.Line(8000, "Was it the other way?"),
			new MediaState.Line(12000, "Drag me anywhere you like"),
			new MediaState.Line(16000, "Change my colours below"));
		long pos = System.currentTimeMillis() % 20000L;
		return new MediaState.Snapshot(true, true, "Preview Song", "Lyrics HUD", pos, 20000, lines, MediaState.LyricsStatus.READY);
	}

	/** Returns the on-screen box that was drawn (in GUI pixels). */
	public static Box draw(DrawContext ctx, LyricsConfig cfg, MediaState.Snapshot s, int screenW, int screenH) {
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		int innerW = Math.max(40, cfg.width - PAD * 2);
		int activeRgb = cfg.activeRgb();
		int upcomingRgb = cfg.upcomingRgb();

		List<Row> rows = new ArrayList<>();
		int y = PAD;
		int barY = -1;

		if (cfg.showHeader) {
			rows.add(new Row(Text.literal(s.playing() ? "NOW PLAYING" : "PAUSED").asOrderedText(), argb(1f, DIM_TEXT), y));
			y += LINE_H + 2;
			rows.add(new Row(Text.literal(marquee(tr, s.title(), innerW, true, cfg.scrollSpeed)).formatted(Formatting.BOLD).asOrderedText(), argb(1f, 0xFFFFFF), y));
			y += LINE_H + 1;
			if (!s.artist().isEmpty()) {
				rows.add(new Row(Text.literal(marquee(tr, s.artist(), innerW, false, cfg.scrollSpeed)).asOrderedText(), argb(1f, DIM_TEXT), y));
				y += LINE_H + 1;
			}
			barY = y + 2;
			y += 2 + 2 + 6;
		}

		// ---- lyric lines ----
		switch (s.lyricsStatus()) {
			case LOADING -> y = addWrapped(rows, tr, "Loading lyrics...", innerW, y, argb(1f, DIM_TEXT), false);
			case NOT_FOUND -> y = addWrapped(rows, tr, "No synced lyrics found", innerW, y, argb(1f, DIM_TEXT), false);
			case READY -> {
				List<MediaState.Line> lines = s.lines();
				long pos = s.posMs() + cfg.offsetMs;
				int idx = -1;
				for (int i = 0; i < lines.size(); i++) {
					if (lines.get(i).timeMs() <= pos) idx = i;
					else break;
				}
				int from = Math.max(0, idx - cfg.linesBefore);
				int to = Math.min(lines.size() - 1, idx + cfg.linesAfter);
				for (int i = from; i <= to; i++) {
					int dist = i - idx;
					String text = lines.get(i).text().isEmpty() ? "♪" : lines.get(i).text();
					int color;
					if (dist == 0) color = argb(1f, activeRgb);
					else color = argb(fade(dist), upcomingRgb);
					y = addWrapped(rows, tr, text, innerW, y, color, dist == 0);
				}
			}
		}

		int h = y - GAP + PAD;
		float scale = cfg.scale;
		int bw = Math.round(cfg.width * scale);
		int bh = Math.round(h * scale);
		int x0 = clamp(Math.round(cfg.x * screenW), 0, Math.max(0, screenW - bw));
		int y0 = clamp(Math.round(cfg.y * screenH), 0, Math.max(0, screenH - bh));

		ctx.getMatrices().pushMatrix();
		ctx.getMatrices().translate(x0, y0);
		ctx.getMatrices().scale(scale, scale);

		int w = cfg.width;
		if (cfg.background) {
			int bg = ((cfg.backgroundOpacity * 255 / 100) << 24) | 0x12121A;
			ctx.fill(1, 0, w - 1, h, bg); // cheap rounded corners
			ctx.fill(0, 1, w, h - 1, bg);
		}

		for (Row r : rows) {
			ctx.drawText(tr, r.text(), PAD, r.y(), r.argb(), cfg.shadow);
		}

		if (barY >= 0) {
			ctx.fill(PAD, barY, PAD + innerW, barY + 2, 0x44FFFFFF);
			if (s.durMs() > 0) {
				int filled = (int) (innerW * Math.min(1.0, (double) s.posMs() / s.durMs()));
				ctx.fill(PAD, barY, PAD + filled, barY + 2, argb(1f, activeRgb));
			}
		}

		ctx.getMatrices().popMatrix();
		return new Box(x0, y0, bw, bh);
	}

	private static int addWrapped(List<Row> rows, TextRenderer tr, String text, int width, int y, int argb, boolean bold) {
		Text t = bold ? Text.literal(text).formatted(Formatting.BOLD) : Text.literal(text);
		for (OrderedText ot : tr.wrapLines(t, width)) {
			rows.add(new Row(ot, argb, y));
			y += LINE_H;
		}
		return y + GAP;
	}

	// ---- scrolling text for names that are too long ----
		private static final long SCROLL_PAUSE_MS = 1800; // rest at the start of each loop

	private static int textWidth(TextRenderer tr, String s, boolean bold) {
		return bold ? tr.getWidth(Text.literal(s).formatted(Formatting.BOLD)) : tr.getWidth(s);
	}

	/** Returns the text unchanged if it fits, otherwise a window of it that slides along over time. */
	private static String marquee(TextRenderer tr, String text, int maxW, boolean bold, int charsPerSec) {
		if (textWidth(tr, text, bold) <= maxW) return text;
		long stepMs = charsPerSec <= 0 ? 0 : Math.max(20, 1000L / charsPerSec);

		String loop = text + "   -   ";
		long cycleMs = loop.length() * Math.max(1, stepMs);
		long m = System.currentTimeMillis() % (cycleMs + SCROLL_PAUSE_MS);
		int start = (stepMs == 0 || m < SCROLL_PAUSE_MS) ? 0 : (int) ((m - SCROLL_PAUSE_MS) / stepMs) % loop.length();
		if (start > 0 && Character.isLowSurrogate(loop.charAt(start))) start++;

		String src = (loop + loop).substring(start);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < src.length(); i++) {
			out.append(src.charAt(i));
			if (textWidth(tr, out.toString(), bold) > maxW) {
				out.setLength(out.length() - 1);
				break;
			}
		}
		return out.toString();
	}

	private static float fade(int dist) {
		if (dist < 0) return dist == -1 ? 0.5f : 0.3f;
		return switch (dist) {
			case 1 -> 0.95f;
			case 2 -> 0.55f;
			default -> 0.35f;
		};
	}

	private static int argb(float alpha, int rgb) {
		int a = Math.max(5, Math.min(255, Math.round(alpha * 255)));
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
