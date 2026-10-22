package dev.lyricshud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LyricsHudClient implements ClientModInitializer {
	public static final String MOD_ID = "lyricshud";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static LyricsConfig config;
	public static MediaState state;

	private static KeyBinding editorKey;
	private static KeyBinding toggleKey;

	@Override
	public void onInitializeClient() {
		config = LyricsConfig.load();
		state = new MediaState();
		new MediaPoller(state, config).start(FabricLoader.getInstance().getConfigDir());

		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
		editorKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.lyricshud.editor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
		toggleKey = KeyBindingHelper.registerKeyBinding(
			new KeyBinding("key.lyricshud.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (editorKey.wasPressed()) {
				mc.setScreen(new LyricsEditScreen(config, state));
			}
			while (toggleKey.wasPressed()) {
				config.enabled = !config.enabled;
				config.save();
			}
		});

		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(MOD_ID, "lyrics"), (ctx, tickCounter) -> {
			MinecraftClient mc = MinecraftClient.getInstance();
			if (!config.enabled || mc.options.hudHidden || mc.currentScreen instanceof LyricsEditScreen) return;
			MediaState.Snapshot snap = state.snapshot();
			if (!snap.hasMedia()) return;
			LyricsRenderer.draw(ctx, config, snap, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight());
		});
	}
}
