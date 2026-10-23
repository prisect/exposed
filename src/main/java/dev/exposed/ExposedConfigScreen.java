package dev.exposed;

import java.util.Locale;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.awt.Desktop;
import java.net.URI;

public final class ExposedConfigScreen extends Screen {
	private static final int[] DISTANCES = {1, 2, 3, 4, 6, 8, 12, 16, 24, 32};
	private static final double[] RED_TIMES = {0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0};
	private static final double UI_SCALE_STEP = 0.05;

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
		int y = 44;
		int step = 23;

		this.option(left, y, width, "Distance: " + config.distanceChunks + " chunks", button -> {
			config.distanceChunks = next(DISTANCES, config.distanceChunks);
			ExposedConfig.save();
			button.setMessage(Component.literal("Distance: " + config.distanceChunks + " chunks"));
		});
		this.option(left, y + step, width, "Red at: " + format(config.redMillis) + " ms", button -> {
			config.redMillis = next(RED_TIMES, config.redMillis);
			ExposedConfig.save();
			button.setMessage(Component.literal("Red at: " + format(config.redMillis) + " ms"));
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

		int sliderWidth = Math.max(120, Math.min(this.width - 20, width * 2 + 12));
		int sliderX = (this.width - sliderWidth) / 2;
		this.addRenderableWidget(new UiScaleSlider(sliderX, y + step * 3, sliderWidth, 20, config.uiScale()));
		this.addRenderableWidget(Button.builder(Component.literal("Visit Exposed's Official page"), button -> {
			try {
				Desktop.getDesktop().browse(URI.create("https://modrinth.com/mod/exposed"));
			} catch (Exception ignored) {
			}
		})
				.bounds(sliderX, y + step * 4, sliderWidth, 20)
				.build());
		this.addRenderableWidget(Button.builder(Component.literal("Reset defaults"), button -> {
			ExposedConfig.reset();
			this.rebuildWidgets();
		}).bounds(this.width / 2 - 154, this.height - 30, 148, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose())
				.bounds(this.width / 2 + 6, this.height - 30, 148, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, Component.literal("EXPOSED SETTINGS"), this.width / 2, 15, 0xFFFFFFFF);
		graphics.centeredText(this.font, Component.literal("PROFILING"), this.width / 2 - 80, 32, 0xFFB8C8D8);
		graphics.centeredText(this.font, Component.literal("DISPLAY"), this.width / 2 + 80, 32, 0xFFB8C8D8);
		graphics.centeredText(this.font, Component.literal("Changes save automatically"), this.width / 2, this.height - 45, 0xFF808080);
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

	private static double snapScale(double value) {
		return Math.round(value / UI_SCALE_STEP) * UI_SCALE_STEP;
	}

	private static String formatScale(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	private static final class UiScaleSlider extends AbstractSliderButton {
		private UiScaleSlider(int x, int y, int width, int height, double initialScale) {
			super(x, y, width, height, Component.empty(), scaleToValue(initialScale));
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			double scale = valueToScale(this.value);
			this.setMessage(Component.literal("UI scale: " + formatScale(scale) + "x"));
		}

		@Override
		protected void applyValue() {
			double scale = valueToScale(this.value);
			ExposedConfig config = ExposedConfig.get();

			if (Math.abs(config.uiScale - scale) > 0.0001) {
				config.uiScale = scale;
				ExposedConfig.save();
			}
		}

		private static double scaleToValue(double scale) {
			double clamped = Math.max(ExposedConfig.MIN_UI_SCALE, Math.min(ExposedConfig.MAX_UI_SCALE, scale));
			return (clamped - ExposedConfig.MIN_UI_SCALE) / (ExposedConfig.MAX_UI_SCALE - ExposedConfig.MIN_UI_SCALE);
		}

		private static double valueToScale(double value) {
			double scale = ExposedConfig.MIN_UI_SCALE
					+ Math.max(0.0, Math.min(1.0, value)) * (ExposedConfig.MAX_UI_SCALE - ExposedConfig.MIN_UI_SCALE);
			return snapScale(scale);
		}
	}
}
