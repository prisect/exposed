package dev.exposed;

/**
 * The single heat ramp shared by the world boxes, entity lines, labels and the
 * ImGui tables. Keeping one implementation means a row that looks red in the
 * menu is the same row that looks red in the world.
 */
public final class Heat {
	private Heat() {
	}

	/**
	 * Returns 0xRRGGBB for a per-tick duration.
	 */
	public static int rgb(double millis) {
		double t = Math.max(0.0, Math.min(1.0, millis / ExposedConfig.get().redMillis()));
		// Ease the ramp so the colour reaches red earlier instead of holding green
		// for the whole first half of the threshold.
		t = Math.sqrt(t);
		int r = (int) Math.round(0x3C + (0xE6 - 0x3C) * Math.min(1.0, t * 1.7));
		int g = (int) Math.round(0xC8 + (0x28 - 0xC8) * t);
		return r << 16 | g << 8 | 0x38;
	}

	/**
	 * Returns an ARGB word for Minecraft gizmos.
	 */
	public static int argb(double millis, int alpha) {
		return argbRgb(rgb(millis), alpha);
	}

	public static int argbRgb(int rgb, int alpha) {
		return (alpha & 0xFF) << 24 | rgb & 0xFFFFFF;
	}

	/**
	 * Returns the ABGR word ImGui draw lists and style colours expect.
	 */
	public static int imgui(double millis, int alpha) {
		return imguiRgb(rgb(millis), alpha);
	}

	public static int imguiRgb(int rgb, int alpha) {
		return (alpha & 0xFF) << 24
				| (rgb & 0xFF) << 16
				| rgb & 0xFF00
				| (rgb >> 16) & 0xFF;
	}
}
