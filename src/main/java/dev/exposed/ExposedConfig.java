package dev.exposed;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExposedConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Logger LOGGER = LoggerFactory.getLogger("Exposed");
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("exposed.json");

	private static ExposedConfig instance = new ExposedConfig();

	public static final double MIN_UI_SCALE = 0.6;
	public static final double MAX_UI_SCALE = 1.6;

	public int distanceChunks = 4;
	public double redMillis = 1.0;
	public double uiScale = 0.8;
	public boolean onlyVisible = true;
	public boolean showLabels = true;
	public boolean showBoxes = true;
	public boolean miniPanelCollapsed = true;
	public float miniPanelX = -1.0F;
	public float miniPanelY = -1.0F;
	public float miniPanelWidth = 240.0F;
	public float miniPanelHeight = 150.0F;

	private ExposedConfig() {
	}

	public static ExposedConfig get() {
		return instance;
	}

	public static void load() {
		if (!Files.exists(PATH)) {
			save();
			return;
		}

		try (Reader reader = Files.newBufferedReader(PATH)) {
			ExposedConfig loaded = GSON.fromJson(reader, ExposedConfig.class);

			if (loaded != null) {
				instance = loaded;
			}
		} catch (Exception e) {
			LOGGER.warn("failed to read config", e);
		}

		instance.clamp();
	}

	public static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(instance));
		} catch (Exception e) {
			LOGGER.warn("failed to save config", e);
		}
	}

	public static void reset() {
		instance = new ExposedConfig();
		save();
	}

	public double distanceBlocks() {
		return this.distanceChunks * 16.0;
	}

	public double redMillis() {
		return Math.max(0.05, this.redMillis);
	}

	public double uiScale() {
		return Math.max(MIN_UI_SCALE, Math.min(MAX_UI_SCALE, this.uiScale));
	}

	private void clamp() {
		this.distanceChunks = Math.max(1, Math.min(32, this.distanceChunks));
		this.redMillis = Math.max(0.05, Math.min(100.0, this.redMillis));
		this.uiScale = this.uiScale();
		this.miniPanelWidth = Math.max(180.0F, Math.min(1000.0F, this.miniPanelWidth));
		this.miniPanelHeight = Math.max(80.0F, Math.min(900.0F, this.miniPanelHeight));
		this.miniPanelX = Math.max(-1.0F, this.miniPanelX);
		this.miniPanelY = Math.max(-1.0F, this.miniPanelY);
	}
}
