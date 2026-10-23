package dev.exposed;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen-space labels for the profiled objects. They are filled by the level
 * render pass (which has the camera/projection) and drawn later by
 * {@link ExposedImGui} with an ImGui font instead of Minecraft's font.
 *
 * <p>Both passes run on the render thread. Entries are pooled so drawing a few
 * hundred labels does not allocate a new object for every label every frame.
 */
public final class WorldLabels {
	private static final List<Label> LABELS = new ArrayList<>();
	private static int size;

	private WorldLabels() {
	}

	public static void clear() {
		size = 0;
	}

	public static void add(float x, float y, int color, String text) {
		add(x, y, color, text, true);
	}

	/**
	 * Adds a label whose left edge sits at {@code x}. Entity labels use this so
	 * they start just to the right of the centre line instead of straddling it.
	 */
	public static void addLeftAligned(float x, float y, int color, String text) {
		add(x, y, color, text, false);
	}

	private static void add(float x, float y, int color, String text, boolean centered) {
		Label label;

		if (size < LABELS.size()) {
			label = LABELS.get(size);
		} else {
			label = new Label();
			LABELS.add(label);
		}

		label.x = x;
		label.y = y;
		label.color = color;
		label.text = text;
		label.centered = centered;
		size++;
	}

	public static int size() {
		return size;
	}

	public static Label get(int index) {
		return LABELS.get(index);
	}

	public static final class Label {
		private float x;
		private float y;
		private int color;
		private String text;
		private boolean centered;

		public float x() {
			return this.x;
		}

		public float y() {
			return this.y;
		}

		public boolean centered() {
			return this.centered;
		}

		public int color() {
			return this.color;
		}

		public String text() {
			return this.text;
		}
	}
}
