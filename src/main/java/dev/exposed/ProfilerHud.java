package dev.exposed;

import java.util.List;

import dev.exposed.network.SubscribePayload;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;

public final class ProfilerHud {
	private static final int WINDOW_FLAGS = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoInputs
			| ImGuiWindowFlags.NoNav | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize
			| ImGuiWindowFlags.NoFocusOnAppearing | ImGuiWindowFlags.NoBringToFrontOnFocus;

	private ProfilerHud() {
	}

	public static boolean shouldRender(Screen screen) {
		if (!Profiler.isEnabled() || !ExposedConfig.get().showHud) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level != null && (screen == null || screen instanceof PauseScreen);
	}

	public static void buildImGui() {
		Minecraft minecraft = Minecraft.getInstance();
		ExposedConfig config = ExposedConfig.get();
		ClientLevel level = minecraft.level;
		boolean local = minecraft.hasSingleplayerServer();
		RemoteFeed.Snapshot snapshot = null;

		if (!local && level != null) {
			snapshot = RemoteFeed.current(level.dimension());
		}

		boolean live = local || snapshot != null;
		List<Profiler.Sample> samples = List.of();
		double average = 0.0;

		if (local && level != null) {
			samples = Profiler.top(level.dimension(), config.hudRows());
			average = Profiler.averageTickMillis();
		} else if (snapshot != null) {
			samples = snapshot.samples();
			average = snapshot.tickMillis();
		}

		boolean supported = local || supportsRemote();
		boolean hasTickData = local ? Profiler.ticks() > 0L : snapshot != null && snapshot.tickMillis() > 0.0;
		int rows = live ? Math.min(config.hudRows(), samples.size()) : 1;
		float guiWidth = minecraft.getWindow().getGuiScaledWidth();

		ImGui.setNextWindowPos(guiWidth - 6.0F, 6.0F, ImGuiCond.Always, 1.0F, 0.0F);
		ImGui.setNextWindowBgAlpha(0.22F);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0.0F);
		ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 4.0F);
		ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.0F, 0.0F, 0.0F, 0.22F);

		if (!ImGui.begin("##exposed-hud", WINDOW_FLAGS)) {
			ImGui.end();
			ImGui.popStyleColor();
			ImGui.popStyleVar(2);
			return;
		}

		ImGui.text("EXPOSED");
		ImGui.sameLine(0.0F, 12.0F);

		if (hasTickData) {
			ImGui.textDisabled(Format.durationTick(average));
		} else {
			ImGui.textDisabled(supported ? "Waiting" : "No server mod");
		}

		ImGui.separator();

		if (!live) {
			ImGui.textDisabled(supported ? "Waiting for server data" : "Install Exposed on the server");
		} else {
			for (int i = 0; i < rows; i++) {
				Profiler.Sample sample = samples.get(i);
				ImGui.text(trim(sample.name(), 14));
				ImGui.sameLine(148.0F);
				ImGui.textDisabled(Format.durationTick(sample.averageMillis()));
			}
		}

		ImGui.end();
		ImGui.popStyleColor();
		ImGui.popStyleVar(2);
	}

	private static boolean supportsRemote() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.getConnection() != null && ClientPlayNetworking.canSend(SubscribePayload.TYPE);
	}

	private static String trim(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "~";
	}
}
