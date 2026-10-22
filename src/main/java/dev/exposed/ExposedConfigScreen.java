package dev.exposed;

import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ExposedConfigScreen extends Screen {
	private static final int[] DISTANCES = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32};
	private static final int[] HUD_ROWS = {2, 3, 4, 5, 6, 8, 10, 12};
	private static final double[] RED_TIMES = {0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0};

	private final Screen parent;

	public ExposedConfigScreen(Screen parent) {
		super(Component.literal("Exposed Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ExposedConfig config = ExposedConfig.get();
		int left = this.width / 2 - 154;
		int right = this.width / 2 + 6;
		int width = 148;
		int y = 38;
		int step = 23;

		this.option(left, y, width, "Distance: " + config.distanceChunks + " chunks", button -> {
			config.distanceChunks = next(DISTANCES, config.distanceChunks);
			ExposedConfig.save();
			button.setMessage(Component.literal("Distance: " + config.distanceChunks + " chunks"));
		});
		this.option(left, y + step, width, "HUD rows: " + config.hudRows, button -> {
			config.hudRows = next(HUD_ROWS, config.hudRows);
			ExposedConfig.save();
			button.setMessage(Component.literal("HUD rows: " + config.hudRows));
		});
		this.option(left, y + step * 2, width, "Red at: " + format(config.redMillis) + " ms", button -> {
			config.redMillis = next(RED_TIMES, config.redMillis);
			ExposedConfig.save();
			button.setMessage(Component.literal("Red at: " + format(config.redMillis) + " ms"));
		});
		this.option(left, y + step * 3, width, "Reset defaults", button -> {
			ExposedConfig.reset();
			this.rebuildWidgets();
		});

		this.option(right, y, width, "Only in view: " + onOff(config.onlyVisible), button -> {
			config.onlyVisible = !config.onlyVisible;
			ExposedConfig.save();
			button.setMessage(Component.literal("Only in view: " + onOff(config.onlyVisible)));
		});
		this.option(right, y + step, width, "Labels: " + onOff(config.showLabels), button -> {
			config.showLabels = !config.showLabels;
			ExposedConfig.save();
			button.setMessage(Component.literal("Labels: " + onOff(config.showLabels)));
		});
		this.option(right, y + step * 2, width, "Fill boxes: " + onOff(config.showBoxes), button -> {
			config.showBoxes = !config.showBoxes;
			ExposedConfig.save();
			button.setMessage(Component.literal("Fill boxes: " + onOff(config.showBoxes)));
		});
		this.option(right, y + step * 3, width, "HUD: " + onOff(config.showHud), button -> {
			config.showHud = !config.showHud;
			ExposedConfig.save();
			button.setMessage(Component.literal("HUD: " + onOff(config.showHud)));
		});

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
				.bounds(this.width / 2 - 50, this.height - 30, 100, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, Component.literal("EXPOSED SETTINGS"), this.width / 2, 15, 0xFFFFFFFF);
		graphics.centeredText(this.font, Component.literal("Changes are saved automatically"), this.width / 2, this.height - 45, 0xFF808080);
	}

	@Override
	public void onClose() {
		ExposedConfig.save();
		this.minecraft.setScreenAndShow(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	private Button option(int x, int y, int width, String text, Button.OnPress press) {
		return this.addRenderableWidget(Button.builder(Component.literal(text), press)
				.bounds(x, y, width, 20)
				.build());
	}

	private static int next(int[] values, int current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) {
				return values[(i + 1) % values.length];
			}
		}

		return values[0];
	}

	private static double next(double[] values, double current) {
		for (int i = 0; i < values.length; i++) {
			if (Math.abs(values[i] - current) < 0.0001) {
				return values[(i + 1) % values.length];
			}
		}

		return values[0];
	}

	private static String onOff(boolean value) {
		return value ? "ON" : "OFF";
	}

	private static String format(double value) {
		if (value == (long) value) {
			return Long.toString((long) value);
		}
		return String.format(Locale.ROOT, "%s", value);
	}
}
