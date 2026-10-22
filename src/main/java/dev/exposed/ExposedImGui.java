package dev.exposed;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;

import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import imgui.gl3.ImGuiImplGl3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class ExposedImGui {
	private static final Logger LOGGER = LoggerFactory.getLogger("Exposed/ImGui");
	private static ImGuiImplGl3 renderer;
	private static boolean contextReady;
	private static boolean backendBroken;
	private static boolean targetBroken;
	private static int framebuffer = -1;
	private static int framebufferTexture = -1;
	private static int framebufferWidth;
	private static int framebufferHeight;
	private static float fontDensity;
	private static long lastFrameNanos;

	public static void init() {
		if (contextReady) {
			return;
		}

		try {
			ImGui.createContext();
			ImGuiIO io = ImGui.getIO();
			io.setIniFilename(null);
			io.setConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
			io.getFonts().addFontDefault();
			ImGui.styleColorsDark();
			ImGui.getStyle().setWindowRounding(4.0F);
			ImGui.getStyle().setFrameRounding(3.0F);
			ImGui.getStyle().setGrabRounding(3.0F);
			ImGui.getStyle().setTabRounding(3.0F);
			contextReady = true;
		} catch (Throwable t) {
			LOGGER.error("imgui init failed", t);
		}
	}

	public static void focus(float mouseX, float mouseY) {
		if (!contextReady) {
			return;
		}

		ImGuiIO io = ImGui.getIO();
		io.clearInputKeys();
		io.addMousePosEvent(mouseX, mouseY);

		for (int button = 0; button < 5; button++) {
			io.addMouseButtonEvent(button, false);
		}
	}

	public static void mouseMove(double mouseX, double mouseY) {
		if (!contextReady) {
			return;
		}

		ImGui.getIO().addMousePosEvent((float) mouseX, (float) mouseY);
	}

	public static void mouseButton(int button, boolean down, double mouseX, double mouseY) {
		if (!contextReady) {
			return;
		}

		ImGuiIO io = ImGui.getIO();
		io.addMousePosEvent((float) mouseX, (float) mouseY);
		int imguiButton = switch (button) {
			case 1 -> 0;
			case 3 -> 1;
			case 2 -> 2;
			case 4 -> 3;
			case 5 -> 4;
			default -> -1;
		};

		if (imguiButton >= 0) {
			io.addMouseButtonEvent(imguiButton, down);
		}
	}

	public static void mouseWheel(double mouseX, double mouseY, double horizontal, double vertical) {
		if (!contextReady) {
			return;
		}

		ImGuiIO io = ImGui.getIO();
		io.addMousePosEvent((float) mouseX, (float) mouseY);
		io.addMouseWheelEvent((float) horizontal, (float) vertical);
	}

	public static void key(int key, boolean down) {
		if (!contextReady) {
			return;
		}

		int imguiKey = imguiKey(key);

		if (imguiKey != ImGuiKey.None) {
			ImGui.getIO().addKeyEvent(imguiKey, down);
		}
	}

	public static void character(int codepoint) {
		if (!contextReady) {
			return;
		}

		ImGui.getIO().addInputCharacter(codepoint);
	}

	public static void render(Minecraft client, RenderTarget target) {
		if (!contextReady || backendBroken || targetBroken) {
			return;
		}

		Screen screen = client.gui.screen();
		ExposedInfoScreen menu = screen instanceof ExposedInfoScreen info ? info : null;
		boolean hud = ProfilerHud.shouldRender(screen);

		if (menu == null && !hud) {
			return;
		}

		if (!ensureRenderer() || !ensureFramebuffer(target)) {
			return;
		}

		float guiWidth = client.getWindow().getGuiScaledWidth();
		float guiHeight = client.getWindow().getGuiScaledHeight();
		float guiScale = Math.max(1.0F, client.getWindow().getGuiScale());

		if (guiWidth <= 0.0F || guiHeight <= 0.0F || target.width <= 0 || target.height <= 0) {
			return;
		}

		if (!ensureFontDensity(guiScale)) {
			return;
		}

		long now = System.nanoTime();
		float delta = lastFrameNanos == 0L ? 1.0F / 60.0F : (now - lastFrameNanos) / 1_000_000_000.0F;
		lastFrameNanos = now;
		delta = Math.max(0.0001F, Math.min(delta, 0.25F));

		ImGuiIO io = ImGui.getIO();
		io.setDisplaySize(guiWidth, guiHeight);
		io.setDisplayFramebufferScale(target.width / guiWidth, target.height / guiHeight);
		io.setDeltaTime(delta);

		if (menu != null) {
			io.addMousePosEvent(menu.cursorX(), menu.cursorY());
		} else {
			io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
		}

		renderer.newFrame();
		ImGui.newFrame();

		if (hud) {
			ProfilerHud.buildImGui();
		}

		if (menu != null) {
			menu.buildImGui();
		}

		ImGui.render();

		int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int[] viewport = new int[4];
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
		GL11.glViewport(0, 0, target.width, target.height);
		renderer.renderDrawData(ImGui.getDrawData());
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
		GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
	}

	private static boolean ensureRenderer() {
		if (renderer != null) {
			return true;
		}

		try {
			ImGuiImplGl3 candidate = new ImGuiImplGl3();

			if (!candidate.init("#version 150")) {
				backendBroken = true;
				LOGGER.error("imgui gl3 init failed");
				return false;
			}

			renderer = candidate;
			return true;
		} catch (Throwable t) {
			backendBroken = true;
			LOGGER.error("imgui gl3 init crashed", t);
			return false;
		}
	}

	private static boolean ensureFontDensity(float density) {
		density = Math.max(1.0F, density);

		if (Math.abs(density - fontDensity) < 0.01F) {
			return true;
		}

		try {
			ImGuiIO io = ImGui.getIO();
			ImFontAtlas atlas = io.getFonts();
			renderer.destroyFontsTexture();
			atlas.clear();

			ImFontConfig config = new ImFontConfig();
			config.setSizePixels(13.0F);
			config.setRasterizerDensity(density);
			config.setOversampleH(1);
			config.setOversampleV(1);
			ImFont font = atlas.addFontDefault(config);
			config.destroy();

			if (font == null) {
				backendBroken = true;
				LOGGER.error("imgui font missing");
				return false;
			}

			io.setFontDefault(font);
			io.setFontGlobalScale(1.0F);

			if (!renderer.createFontsTexture()) {
				backendBroken = true;
				LOGGER.error("imgui font texture upload failed");
				return false;
			}

			fontDensity = density;
			return true;
		} catch (Throwable t) {
			backendBroken = true;
			LOGGER.error("imgui font setup failed", t);
			return false;
		}
	}

	private static boolean ensureFramebuffer(RenderTarget target) {
		GpuTexture texture = target.getColorTexture();

		if (!(texture instanceof GlTexture glTexture)) {
			targetBroken = true;
			LOGGER.error("imgui needs opengl, got {}", texture == null ? "null" : texture.getClass().getName());
			return false;
		}

		int textureId = glTexture.glId();

		if (framebuffer != -1 && framebufferTexture == textureId && framebufferWidth == target.width
				&& framebufferHeight == target.height) {
			return true;
		}

		int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

		if (framebuffer != -1) {
			GL30.glDeleteFramebuffers(framebuffer);
			framebuffer = -1;
		}

		framebuffer = GL30.glGenFramebuffers();
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId,
				0);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);

		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			GL30.glDeleteFramebuffers(framebuffer);
			framebuffer = -1;
			targetBroken = true;
			LOGGER.error("imgui fbo incomplete: 0x{}", Integer.toHexString(status));
			return false;
		}

		framebufferTexture = textureId;
		framebufferWidth = target.width;
		framebufferHeight = target.height;
		return true;
	}

	private static int imguiKey(int key) {
		if (key >= 4 && key <= 29) {
			return ImGuiKey.A + key - 4;
		}

		if (key >= 30 && key <= 38) {
			return ImGuiKey._1 + key - 30;
		}

		if (key == 39) {
			return ImGuiKey._0;
		}

		if (key >= 58 && key <= 69) {
			return ImGuiKey.F1 + key - 58;
		}

		return switch (key) {
			case 40 -> ImGuiKey.Enter;
			case 41 -> ImGuiKey.Escape;
			case 42 -> ImGuiKey.Backspace;
			case 43 -> ImGuiKey.Tab;
			case 44 -> ImGuiKey.Space;
			case 45 -> ImGuiKey.Minus;
			case 46 -> ImGuiKey.Equal;
			case 47 -> ImGuiKey.LeftBracket;
			case 48 -> ImGuiKey.Backslash;
			case 49 -> ImGuiKey.RightBracket;
			case 51 -> ImGuiKey.Semicolon;
			case 52 -> ImGuiKey.Apostrophe;
			case 53 -> ImGuiKey.GraveAccent;
			case 54 -> ImGuiKey.Comma;
			case 55 -> ImGuiKey.Period;
			case 56 -> ImGuiKey.Slash;
			case 57 -> ImGuiKey.CapsLock;
			case 70 -> ImGuiKey.PrintScreen;
			case 71 -> ImGuiKey.ScrollLock;
			case 72 -> ImGuiKey.Pause;
			case 73 -> ImGuiKey.Insert;
			case 74 -> ImGuiKey.Home;
			case 75 -> ImGuiKey.PageUp;
			case 76 -> ImGuiKey.Delete;
			case 77 -> ImGuiKey.End;
			case 78 -> ImGuiKey.PageDown;
			case 79 -> ImGuiKey.RightArrow;
			case 80 -> ImGuiKey.LeftArrow;
			case 81 -> ImGuiKey.DownArrow;
			case 82 -> ImGuiKey.UpArrow;
			case 83 -> ImGuiKey.NumLock;
			case 84 -> ImGuiKey.KeypadDivide;
			case 85 -> ImGuiKey.KeypadMultiply;
			case 86 -> ImGuiKey.KeypadSubtract;
			case 87 -> ImGuiKey.KeypadAdd;
			case 88 -> ImGuiKey.KeypadEnter;
			case 89 -> ImGuiKey.Keypad1;
			case 90 -> ImGuiKey.Keypad2;
			case 91 -> ImGuiKey.Keypad3;
			case 92 -> ImGuiKey.Keypad4;
			case 93 -> ImGuiKey.Keypad5;
			case 94 -> ImGuiKey.Keypad6;
			case 95 -> ImGuiKey.Keypad7;
			case 96 -> ImGuiKey.Keypad8;
			case 97 -> ImGuiKey.Keypad9;
			case 98 -> ImGuiKey.Keypad0;
			case 99 -> ImGuiKey.KeypadDecimal;
			case 100 -> ImGuiKey.Oem102;
			case 101 -> ImGuiKey.Menu;
			case 224 -> ImGuiKey.LeftCtrl;
			case 225 -> ImGuiKey.LeftShift;
			case 226 -> ImGuiKey.LeftAlt;
			case 227 -> ImGuiKey.LeftSuper;
			case 228 -> ImGuiKey.RightCtrl;
			case 229 -> ImGuiKey.RightShift;
			case 230 -> ImGuiKey.RightAlt;
			case 231 -> ImGuiKey.RightSuper;
			default -> ImGuiKey.None;
		};
	}
}
