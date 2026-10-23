package dev.exposed;

import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import dev.exposed.network.SubscribePayload;

public final class ExposedImGui {
	private static final Logger LOGGER = LoggerFactory.getLogger("Exposed/ImGui");
	/**
	 * ImGui's default font/layout is authored around a 13px font. Minecraft's UI is
	 * authored around a ~10px font, so rendering ImGui at its default size makes it
	 * look oversized once the in-game GUI scale gets cranked up. Matching the
	 * vanilla size keeps ImGui on exactly the same logical unit as the rest of the
	 * HUD: it grows and shrinks with the Minecraft GUI scale instead of sitting at
	 * its own fixed size.
	 */
	private static final float BASE_FONT_SIZE = 13.0F;
	private static final float UI_FONT_SIZE = 10.0F;
	private static final float UI_SCALE = UI_FONT_SIZE / BASE_FONT_SIZE;
	private static ImGuiImplGl3 renderer;
	private static boolean contextReady;
	private static boolean backendBroken;
	private static boolean targetBroken;
	private static int framebuffer = -1;
	private static int framebufferTexture = -1;
	private static int framebufferWidth;
	private static int framebufferHeight;
	private static float fontDensity;
	private static float appliedStyleScale = 1.0F;
	private static float appliedUiScale = -1.0F;
	private static float fontSize = UI_FONT_SIZE;
	private static long lastFrameNanos;
	private static long miniRefreshAt;
	private static ResourceKey<Level> miniDimension;
	private static double miniTickMillis;
	private static int miniEntities;
	private static int miniBlocks;
	private static int miniObjects;
	private static String miniHotLabel = "No profiled objects";
	private static double miniHotMillis;
	private static String miniNoticeTitle;
	private static String miniNoticeText;
	private static boolean miniPanelActive;
	private static boolean miniMouseCaptured;
	private static boolean chatMouseKnown;
	private static double chatMouseX = -Double.MAX_VALUE;
	private static double chatMouseY = -Double.MAX_VALUE;
	private static float miniX;
	private static float miniY;
	private static float miniWidth;
	private static float miniHeight;
	private static final ImVec2 WINDOW_POS = new ImVec2();
	private static final ImVec2 WINDOW_SIZE = new ImVec2();
	private static final int[] VIEWPORT = new int[4];

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
			applyStyle(uiScale());
			contextReady = true;
		} catch (Throwable t) {
			LOGGER.error("imgui init failed", t);
		}
	}

	/**
	 * Current user-selected ImGui scale. All fixed sizes in the menu are passed
	 * through {@link #scaled(float)} so widening the font does not clip columns.
	 */
	public static float uiScale() {
		return (float) ExposedConfig.get().uiScale();
	}

	public static float scaled(float value) {
		return value * uiScale();
	}

	public static float fontSize() {
		return fontSize;
	}

	private static void applyStyle(float scale) {
		ImGui.styleColorsDark();
		float requested = UI_SCALE * scale;
		ImGui.getStyle().scaleAllSizes(requested / appliedStyleScale);
		appliedStyleScale = requested;
		// scaleAllSizes() truncates these 1px defaults to zero, which trips Dear
		// ImGui's internal assertions (SeparatorSize > 0).
		ImGui.getStyle().setSeparatorSize(1.0F);
		ImGui.getStyle().setWindowBorderSize(1.0F);
		ImGui.getStyle().setWindowRounding(Math.max(1.0F, 4.0F * scale));
		ImGui.getStyle().setFrameRounding(Math.max(1.0F, 3.0F * scale));
		ImGui.getStyle().setGrabRounding(Math.max(1.0F, 3.0F * scale));
		ImGui.getStyle().setTabRounding(Math.max(1.0F, 3.0F * scale));
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
		ExposedConfig config = ExposedConfig.get();
		/*
		 * In-world time labels use ImGui's font instead of Minecraft's billboard
		 * font. They are only drawn while playing (no screen open) and only when
		 * the level pass actually produced labels.
		 */
		boolean showInWorld = screen == null || screen instanceof ChatScreen;
		boolean worldLabels = menu == null && showInWorld && Profiler.isEnabled() && config.showLabels
				&& WorldLabels.size() > 0;
		boolean miniPanel = menu == null && showInWorld && Profiler.isEnabled();
		miniPanelActive = miniPanel;

		if (menu == null && !worldLabels && !miniPanel) {
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
		syncUiScale();

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
		} else if (miniPanel && screen instanceof ChatScreen && chatMouseKnown) {
			io.addMousePosEvent((float) chatMouseX, (float) chatMouseY);
		} else {
			io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
		}

		renderer.newFrame();
		ImGui.newFrame();

		if (menu != null) {
			menu.buildImGui();
		} else if (worldLabels) {
			drawWorldLabels();
		}
		if (miniPanel) {
			drawMiniPanel(guiWidth, guiHeight);
		}

		ImGui.render();

		int previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
		GL11.glViewport(0, 0, target.width, target.height);
		renderer.renderDrawData(ImGui.getDrawData());
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer);
		GL11.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
	}

	private static void drawMiniPanel(float displayWidth, float displayHeight) {
		ExposedConfig config = ExposedConfig.get();
		refreshMiniPanel();
		float width = config.miniPanelWidth;
		float height = config.miniPanelHeight;
		float x = config.miniPanelX >= 0.0F ? config.miniPanelX : Math.max(0.0F, displayWidth - width - 8.0F);
		float y = config.miniPanelY >= 0.0F ? config.miniPanelY : 8.0F;
		x = Math.min(x, Math.max(0.0F, displayWidth - 80.0F));
		y = Math.min(y, Math.max(0.0F, displayHeight - 32.0F));
		ImGui.setNextWindowPos(x, y, ImGuiCond.FirstUseEver);
		ImGui.setNextWindowSize(width, height, ImGuiCond.FirstUseEver);
		ImGui.setNextWindowSizeConstraints(ExposedImGui.scaled(150.0F), ExposedImGui.scaled(55.0F),
				Math.max(ExposedImGui.scaled(150.0F), displayWidth), Math.max(ExposedImGui.scaled(55.0F), displayHeight));
		ImGui.setNextWindowCollapsed(config.miniPanelCollapsed, ImGuiCond.FirstUseEver);
		ImGui.setNextWindowBgAlpha(0.88F);
		String title = "Exposed  " + Format.durationTick(miniTickMillis) + "  "
				+ Format.decimal1(tps(miniTickMillis)) + " TPS###exposed-mini";
		int flags = ImGuiWindowFlags.NoSavedSettings;
		if (ImGui.begin(title, flags)) {
			ImGui.text(Format.count(miniObjects) + " objects   " + Format.count(miniEntities) + " entities   "
					+ Format.count(miniBlocks) + " block entities");
			if (miniHotMillis > 0.0) {
				ImGui.text("Hottest: " + miniHotLabel + "   " + Format.durationTick(miniHotMillis));
			} else {
				ImGui.textDisabled("No active tick samples; nearby inactive objects are gray.");
			}
			if (ImGui.smallButton("Open full info")) {
				Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreenAndShow(new ExposedInfoScreen(null)));
			}
		}
		boolean collapsed = ImGui.isWindowCollapsed();
		ImGui.getWindowPos(WINDOW_POS);
		ImGui.getWindowSize(WINDOW_SIZE);
		miniX = WINDOW_POS.x;
		miniY = WINDOW_POS.y;
		miniWidth = WINDOW_SIZE.x;
		miniHeight = WINDOW_SIZE.y;
		ImGui.end();

		if (config.miniPanelCollapsed != collapsed
				|| Math.abs(config.miniPanelX - WINDOW_POS.x) > 1.0F
				|| Math.abs(config.miniPanelY - WINDOW_POS.y) > 1.0F
				|| Math.abs(config.miniPanelWidth - WINDOW_SIZE.x) > 1.0F
				|| (!collapsed && Math.abs(config.miniPanelHeight - WINDOW_SIZE.y) > 1.0F)) {
			config.miniPanelCollapsed = collapsed;
			config.miniPanelX = WINDOW_POS.x;
			config.miniPanelY = WINDOW_POS.y;
			config.miniPanelWidth = WINDOW_SIZE.x;
			if (!collapsed) {
				config.miniPanelHeight = WINDOW_SIZE.y;
			}
			ExposedConfig.save();
		}
		if (miniNoticeTitle != null) {
			float noticeY = WINDOW_POS.y + (collapsed ? ExposedImGui.scaled(29.0F) : WINDOW_SIZE.y) + 5.0F;
			ImGui.setNextWindowPos(WINDOW_POS.x, noticeY, ImGuiCond.Always);
			ImGui.setNextWindowSize(ExposedImGui.scaled(270.0F), 0.0F, ImGuiCond.Always);
			ImGui.setNextWindowBgAlpha(0.94F);
			int noticeFlags = ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoMove
					| ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize;
			if (ImGui.begin("Information###exposed-server-notice", noticeFlags)) {
				ImGui.textWrapped(miniNoticeText);
			}
			ImGui.end();
		}
	}

	public static void overlayMouseMove(double mouseX, double mouseY) {
		chatMouseX = mouseX;
		chatMouseY = mouseY;
		chatMouseKnown = true;
		if (contextReady && miniPanelActive) {
			ImGui.getIO().addMousePosEvent((float) mouseX, (float) mouseY);
		}
	}

	public static boolean overlayMouseButton(int button, boolean down, double mouseX, double mouseY) {
		boolean inside = mouseX >= miniX && mouseX <= miniX + miniWidth
				&& mouseY >= miniY && mouseY <= miniY + miniHeight;
		if (!contextReady || !miniPanelActive || (down && !inside) || (!down && !miniMouseCaptured)) {
			return false;
		}
		chatMouseX = mouseX;
		chatMouseY = mouseY;
		chatMouseKnown = true;
		mouseButton(button, down, mouseX, mouseY);
		miniMouseCaptured = down;
		return true;
	}

	public static boolean overlayMouseDragged(double mouseX, double mouseY) {
		if (!miniMouseCaptured) {
			return false;
		}
		overlayMouseMove(mouseX, mouseY);
		return true;
	}

	public static boolean overlayMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		boolean inside = mouseX >= miniX && mouseX <= miniX + miniWidth
				&& mouseY >= miniY && mouseY <= miniY + miniHeight;
		if (!contextReady || !miniPanelActive || !inside) {
			return false;
		}
		mouseWheel(mouseX, mouseY, horizontal, vertical);
		return true;
	}

	private static void refreshMiniPanel() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		ResourceKey<Level> dimension = level == null ? null : level.dimension();
		long now = System.currentTimeMillis();
		if (now - miniRefreshAt < 200L && java.util.Objects.equals(dimension, miniDimension)) {
			return;
		}
		miniRefreshAt = now;
		miniDimension = dimension;
		List<Profiler.Sample> samples;
		if (minecraft.hasSingleplayerServer() && dimension != null) {
			miniTickMillis = Profiler.averageTickMillis();
			samples = Profiler.all(dimension);
		} else {
			RemoteFeed.Snapshot snapshot = dimension == null ? null : RemoteFeed.current(dimension);
			miniTickMillis = snapshot == null ? 0.0 : snapshot.tickMillis();
			samples = snapshot == null ? List.of() : snapshot.samples();
		}
		miniObjects = samples.size();
		miniNoticeTitle = null;
		miniNoticeText = null;
		if (!minecraft.hasSingleplayerServer()) {
			if (!ClientPlayNetworking.canSend(SubscribePayload.TYPE)) {
				miniNoticeTitle = "Information";
				miniNoticeText = "Server data unavailable: This server does not advertise Exposed's profile channel. Exposed server component not installed.";
			} else if (RemoteFeed.current(dimension) == null) {
				miniNoticeTitle = "Information";
				miniNoticeText = "Server data unavailable: The server supports Exposed, but no profile snapshot has arrived. Ask an administrator to check profiling and access settings.";
			} else if (samples.isEmpty()) {
				miniNoticeTitle = "Information";
				miniNoticeText = "Server data unavailable: The server feed is connected, but no entity or block-entity tick samples have been reported yet.";
			}
		}
		miniEntities = 0;
		miniBlocks = 0;
		miniHotLabel = "No profiled objects";
		miniHotMillis = 0.0;
		for (Profiler.Sample sample : samples) {
			if (sample.block) miniBlocks++; else miniEntities++;
			if (sample.averageMillis() > miniHotMillis) {
				miniHotMillis = sample.averageMillis();
				miniHotLabel = sample.name();
			}
		}
	}

	private static double tps(double tickMillis) {
		return tickMillis > 0.0 ? Math.min(20.0, 1000.0 / Math.max(50.0, tickMillis)) : 20.0;
	}

	private static void drawWorldLabels() {
		ImFont font = ImGui.getFont();
		float size = fontSize;
		ImDrawList drawList = ImGui.getForegroundDrawList();
		int count = WorldLabels.size();

		for (int i = 0; i < count; i++) {
			WorldLabels.Label label = WorldLabels.get(i);
			String text = label.text();

			if (text == null || text.isEmpty()) {
				continue;
			}

			// ProggyClean is monospace; around 0.56em per glyph. Approximate the
			// width so centred labels do not allocate an ImVec2 per label. Labels
			// placed beside an entity line are already left-aligned.
			float halfWidth = text.length() * size * 0.28F;
			float x = label.centered() ? label.x() - halfWidth : label.x();
			int fontSize = Math.max(1, Math.round(size));
			// A cheap one-pixel shadow keeps the text legible against bright skies
			// and white blocks without drawing a full label background.
			drawList.addText(font, fontSize, x + 1.0F, label.y() + 1.0F, 0xC0000000, text);
			drawList.addText(font, fontSize, x, label.y(), label.color(), text);
		}

		// The level pass refills this list next frame. Clearing here means a frame
		// where that pass did not run can never reuse old screen positions.
		WorldLabels.clear();
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
		float scale = uiScale();

		if (Math.abs(density - fontDensity) < 0.01F) {
			return true;
		}

		try {
			ImGuiIO io = ImGui.getIO();
			ImFontAtlas atlas = io.getFonts();
			renderer.destroyFontsTexture();
			atlas.clear();

			// Bake at the largest supported user scale so smaller settings sample a
			// higher-resolution atlas instead of magnifying tiny glyphs.
			float pixelSize = UI_FONT_SIZE * (float) ExposedConfig.MAX_UI_SCALE;
			ImFontConfig config = new ImFontConfig();
			config.setSizePixels(pixelSize);
			config.setRasterizerDensity(density);
			config.setOversampleH(2);
			config.setOversampleV(2);
			ImFont font = atlas.addFontDefault(config);
			config.destroy();

			if (font == null) {
				backendBroken = true;
				LOGGER.error("imgui font missing");
				return false;
			}

			// One font size for every part of the UI (including table values and
			// world labels) so no column is visibly smaller than its neighbours.
			io.setFontDefault(font);
			io.setFontGlobalScale(scale / (float) ExposedConfig.MAX_UI_SCALE);

			if (!renderer.createFontsTexture()) {
				backendBroken = true;
				LOGGER.error("imgui font texture upload failed");
				return false;
			}

			fontSize = UI_FONT_SIZE * scale;
			fontDensity = density;
			return true;
		} catch (Throwable t) {
			backendBroken = true;
			LOGGER.error("imgui font setup failed", t);
			return false;
		}
	}

	/** Applies scale changes without clearing ImGui's font atlas mid-session. */
	private static void syncUiScale() {
		float scale = uiScale();
		if (Math.abs(scale - appliedUiScale) < 0.001F) {
			return;
		}

		ImGui.getIO().setFontGlobalScale(scale / (float) ExposedConfig.MAX_UI_SCALE);
		applyStyle(scale);
		fontSize = UI_FONT_SIZE * scale;
		appliedUiScale = scale;
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
