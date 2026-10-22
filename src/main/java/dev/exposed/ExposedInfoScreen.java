package dev.exposed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.exposed.network.SnapshotPayload;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

public final class ExposedInfoScreen extends Screen {
	private static final int SHADE = 0x90000000;
	private static final int WINDOW_FLAGS = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoResize
			| ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoSavedSettings;
	private static final int TABLE_FLAGS = ImGuiTableFlags.BordersOuterH | ImGuiTableFlags.BordersInnerH
			| ImGuiTableFlags.RowBg | ImGuiTableFlags.ScrollY | ImGuiTableFlags.SizingStretchProp
			| ImGuiTableFlags.NoSavedSettings;

	private final Screen parent;
	private Mode mode = Mode.LIVE;
	private double cursorX;
	private double cursorY;

	public ExposedInfoScreen(Screen parent) {
		super(Component.literal("Exposed"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.cursorX = this.width / 2.0;
		this.cursorY = this.height / 2.0;
		ExposedImGui.focus((float) this.cursorX, (float) this.cursorY);
		addRenderableWidget(Button.builder(Component.literal("Back to Game"), button -> this.minecraft.gui.setScreen(null))
				.bounds(this.width / 2 - 60, Math.max(8, this.height - 30), 120, 20)
				.build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, SHADE);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	public void buildImGui() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		List<Profiler.Sample> samples = samples(level);
		double tickMillis = tickMillis(minecraft);
		List<ProfileRow> types = typeRows(samples, tickMillis);
		List<ProfileRow> singles = singleRows(samples, tickMillis);
		List<SubscriberRow> subscribers = subscriberRows(minecraft);

		ImGui.setNextWindowPos(8.0F, 8.0F, ImGuiCond.Always);
		ImGui.setNextWindowSize(Math.max(320.0F, this.width - 16.0F), Math.max(220.0F, this.height - 56.0F),
				ImGuiCond.Always);
		ImGui.setNextWindowBgAlpha(0.96F);
		ImBoolean open = new ImBoolean(true);

		if (!ImGui.begin("Exposed", open, WINDOW_FLAGS)) {
			ImGui.end();

			if (!open.get()) {
				minecraft.execute(this::onClose);
			}

			return;
		}

		float footer = ImGui.getFrameHeight() + ImGui.getStyle().getItemSpacingY() * 2.0F;
		ImGui.beginChild("##exposed-main", 0.0F, -footer, false);

		if (tickMillis > 0.0) {
			ImGui.text("Server tick  " + Format.durationTick(tickMillis));
		} else {
			ImGui.textDisabled("Server tick  waiting");
		}

		ImGui.sameLine();
		ImGui.textDisabled("(" + Format.count(samples.size()) + " active)");
		ImGui.separator();

		if (ImGui.beginTabBar("##exposed-mode")) {
			if (ImGui.beginTabItem("Live")) {
				this.mode = Mode.LIVE;
				this.body(subscribers, types, singles, tickMillis);
				ImGui.endTabItem();
			}

			if (ImGui.beginTabItem("All time")) {
				this.mode = Mode.ALL_TIME;
				this.body(subscribers, types, singles, tickMillis);
				ImGui.endTabItem();
			}

			ImGui.endTabBar();
		}

		ImGui.endChild();
		ImGui.separator();

		if (ImGui.button("Settings", 88.0F, 0.0F)) {
			minecraft.execute(() -> minecraft.setScreenAndShow(new ExposedConfigScreen(this)));
		}

		ImGui.sameLine();

		if (ImGui.button("Close", 88.0F, 0.0F)) {
			minecraft.execute(this::onClose);
		}

		ImGui.sameLine();
		ImGui.textDisabled(this.mode == Mode.LIVE ? "Per-tick averages" : "Totals since capture started");
		ImGui.end();

		if (!open.get()) {
			minecraft.execute(this::onClose);
		}
	}

	private void body(List<SubscriberRow> subscribers, List<ProfileRow> types, List<ProfileRow> singles,
			double tickMillis) {
		boolean live = this.mode == Mode.LIVE;
		sort(types, live);
		sort(singles, live);
		ImGui.beginChild("##exposed-body", 0.0F, 0.0F, false);

		float availableWidth = ImGui.getContentRegionAvailX();
		float leftWidth = availableWidth * 0.36F;
		ImGui.beginChild("##exposed-subscribers", leftWidth, 0.0F, true);
		ImGui.textDisabled("Server thread");
		ImGui.sameLine();
		ImGui.textDisabled(Format.count(subscribers.size()));
		ImGui.separator();
		this.subscriberTable(subscribers);
		ImGui.endChild();

		ImGui.sameLine();
		ImGui.beginChild("##exposed-profiles", 0.0F, 0.0F, false);
		float listHeight = Math.max(90.0F,
				ImGui.getContentRegionAvailY() * 0.5F - ImGui.getTextLineHeightWithSpacing());

		ImGui.textDisabled("By type");
		ImGui.sameLine();
		ImGui.textDisabled(Format.count(types.size()));
		ImGui.beginChild("##exposed-types", 0.0F, listHeight, true);
		this.profileTable("##exposed-types-table", types, tickMillis, live);
		ImGui.endChild();

		ImGui.textDisabled("Individual");
		ImGui.sameLine();
		ImGui.textDisabled(Format.count(singles.size()));
		ImGui.beginChild("##exposed-individual", 0.0F, 0.0F, true);
		this.profileTable("##exposed-individual-table", singles, tickMillis, live);
		ImGui.endChild();
		ImGui.endChild();
		ImGui.endChild();
	}

	private void subscriberTable(List<SubscriberRow> rows) {
		if (!ImGui.beginTable("##exposed-subscriber-table", 3, TABLE_FLAGS)) {
			return;
		}

		ImGui.tableSetupColumn("Mod / jar", ImGuiTableColumnFlags.WidthStretch, 1.1F);
		ImGui.tableSetupColumn("Subscriber", ImGuiTableColumnFlags.WidthStretch, 1.7F);
		ImGui.tableSetupColumn("%", ImGuiTableColumnFlags.WidthFixed, 46.0F);
		ImGui.tableSetupScrollFreeze(0, 1);
		ImGui.tableHeadersRow();

		if (rows.isEmpty()) {
			ImGui.tableNextRow();
			ImGui.tableNextColumn();
			ImGui.textDisabled("Waiting for server thread data");
		}

		for (SubscriberRow row : rows) {
			ImGui.tableNextRow();
			ImGui.tableNextColumn();
			ImGui.textWrapped(row.owner() + "  " + row.jar());
			ImGui.tableNextColumn();
			ImGui.textWrapped(row.className() + "." + row.methodName());
			ImGui.tableNextColumn();
			ImGui.textDisabled(String.format(Locale.ROOT, "%.1f%%", row.percent()));
		}

		ImGui.endTable();
	}

	private void profileTable(String id, List<ProfileRow> rows, double tickMillis, boolean live) {
		if (!ImGui.beginTable(id, 4, TABLE_FLAGS)) {
			return;
		}

		ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, 20.0F);
		ImGui.tableSetupColumn("Type / name", ImGuiTableColumnFlags.WidthStretch, 1.0F);
		ImGui.tableSetupColumn("Avg/tick", ImGuiTableColumnFlags.WidthFixed, 76.0F);
		ImGui.tableSetupColumn("%", ImGuiTableColumnFlags.WidthFixed, 48.0F);
		ImGui.tableSetupScrollFreeze(0, 1);
		ImGui.tableHeadersRow();

		if (rows.isEmpty()) {
			ImGui.tableNextRow();
			ImGui.tableNextColumn();
			ImGui.textDisabled("No data");
		}

		for (ProfileRow row : rows) {
			double millis = live ? row.liveMillis() : row.allTimeMillis();
			ImGui.tableNextRow();
			ImGui.tableNextColumn();
			ImGui.textDisabled(row.block() ? "B" : "E");
			ImGui.tableNextColumn();
			ImGui.text(row.label());
			ImGui.tableNextColumn();
			this.valueText(millis, tickMillis, Format.durationTick(millis));
			ImGui.tableNextColumn();
			ImGui.textDisabled(Format.percent(millis, row.tickMillis()));
		}

		ImGui.endTable();
	}

	private void valueText(double millis, double tickMillis, String value) {
		double share = tickMillis > 0.0 ? millis / tickMillis : 0.0;
		float alpha = share >= 0.5 ? 1.0F : share >= 0.2 ? 0.85F : share >= 0.05 ? 0.7F : 0.55F;
		ImGui.textColored(1.0F, 1.0F, 1.0F, alpha, value);
	}

	private void sort(List<ProfileRow> rows, boolean live) {
		Comparator<ProfileRow> comparator = live
				? Comparator.comparingDouble(ProfileRow::liveMillis)
				: Comparator.comparingDouble(ProfileRow::allTimeMillis);
		rows.sort(comparator.reversed());
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		this.cursorX = mouseX;
		this.cursorY = mouseY;
		ExposedImGui.mouseMove(mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
		this.cursorX = event.x();
		this.cursorY = event.y();

		if (super.mouseClicked(event, doubled)) {
			return true;
		}

		ExposedImGui.mouseButton(event.button(), true, event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.cursorX = event.x();
		this.cursorY = event.y();
		super.mouseReleased(event);
		ExposedImGui.mouseButton(event.button(), false, event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		this.cursorX = event.x();
		this.cursorY = event.y();
		ExposedImGui.mouseMove(event.x(), event.y());
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		this.cursorX = mouseX;
		this.cursorY = mouseY;
		ExposedImGui.mouseWheel(mouseX, mouseY, horizontal, vertical);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			this.onClose();
			return true;
		}

		ExposedImGui.key(event.key(), true);
		return true;
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		ExposedImGui.key(event.key(), false);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		ExposedImGui.character(event.codepoint());
		return true;
	}

	@Override
	public void onClose() {
		if (this.parent == null) {
			this.minecraft.gui.setScreen(null);
		} else {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	public float cursorX() {
		return (float) this.cursorX;
	}

	public float cursorY() {
		return (float) this.cursorY;
	}

	private List<SubscriberRow> subscriberRows(Minecraft minecraft) {
		List<SubscriberRow> rows = new ArrayList<>();

		if (minecraft.hasSingleplayerServer()) {
			for (ServerThreadProfiler.Entry entry : ServerThreadProfiler.top(64)) {
				rows.add(new SubscriberRow(entry.owner(), entry.jar(), entry.className(), entry.methodName(),
						entry.percent()));
			}

			return rows;
		}

		RemoteFeed.Snapshot snapshot = minecraft.level == null ? null : RemoteFeed.current(minecraft.level.dimension());

		if (snapshot != null) {
			for (SnapshotPayload.Subscriber entry : snapshot.subscribers()) {
				rows.add(new SubscriberRow(entry.owner(), entry.jar(), entry.className(), entry.methodName(),
						entry.percent()));
			}
		}

		return rows;
	}

	private List<ProfileRow> typeRows(List<Profiler.Sample> samples, double tickMillis) {
		Map<String, TypeAccumulator> groups = new HashMap<>();

		for (Profiler.Sample sample : samples) {
			String key = (sample.block ? "B:" : "E:") + sample.type();
			TypeAccumulator group = groups.computeIfAbsent(key,
					ignored -> new TypeAccumulator(sample.type(), sample.block));
			group.add(sample);
		}

		List<ProfileRow> rows = new ArrayList<>(groups.size());

		for (TypeAccumulator group : groups.values()) {
			rows.add(group.row(tickMillis));
		}

		return rows;
	}

	private List<ProfileRow> singleRows(List<Profiler.Sample> samples, double tickMillis) {
		List<ProfileRow> rows = new ArrayList<>(samples.size());

		for (Profiler.Sample sample : samples) {
			rows.add(new ProfileRow(sample.name(), sample.averageMillis(), sample.allTimeMillis(), tickMillis,
					sample.block));
		}

		return rows;
	}

	private static List<Profiler.Sample> samples(ClientLevel level) {
		if (level == null) {
			return List.of();
		}

		if (Minecraft.getInstance().hasSingleplayerServer()) {
			return Profiler.all(level.dimension());
		}

		RemoteFeed.Snapshot snapshot = RemoteFeed.current(level.dimension());
		return snapshot == null ? List.of() : snapshot.samples();
	}

	private static double tickMillis(Minecraft minecraft) {
		if (minecraft.hasSingleplayerServer()) {
			return Profiler.averageTickMillis();
		}

		RemoteFeed.Snapshot snapshot = minecraft.level == null ? null : RemoteFeed.current(minecraft.level.dimension());
		return snapshot == null ? 0.0 : snapshot.tickMillis();
	}

	private enum Mode {
		LIVE,
		ALL_TIME
	}

	private record SubscriberRow(String owner, String jar, String className, String methodName, double percent) {
	}

	private record ProfileRow(String label, double liveMillis, double allTimeMillis, double tickMillis,
			boolean block) {
	}

	private static final class TypeAccumulator {
		private final String type;
		private final boolean block;
		private double live;
		private double allTime;

		private TypeAccumulator(String type, boolean block) {
			this.type = type;
			this.block = block;
		}

		private void add(Profiler.Sample sample) {
			this.live += sample.averageMillis();
			this.allTime += sample.allTimeMillis();
		}

		private ProfileRow row(double tickMillis) {
			return new ProfileRow(this.type, this.live, this.allTime, tickMillis, this.block);
		}
	}
}
