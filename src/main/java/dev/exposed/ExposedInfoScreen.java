package dev.exposed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.exposed.network.SnapshotPayload;
import dev.exposed.network.SubscribePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;

public final class ExposedInfoScreen extends Screen {
	private static final int SHADE = 0x90000000;
	private static final int WINDOW_FLAGS = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoSavedSettings;
	private static final int TABLE_FLAGS = ImGuiTableFlags.BordersOuterH | ImGuiTableFlags.BordersInnerH
			| ImGuiTableFlags.RowBg | ImGuiTableFlags.ScrollY | ImGuiTableFlags.SizingStretchProp
			| ImGuiTableFlags.NoSavedSettings;
	private static final int HISTORY_SIZE = 120;
	private static final long REFRESH_MILLIS = 200L;
	/**
	 * The old window filled nearly the whole screen (width - 16 by height - 56).
	 * These ratios plus the hard caps keep it clearly smaller at every GUI scale,
	 * and centre it instead of pinning it to the top-left corner.
	 */
	private static final float WIDTH_RATIO = 0.70F;
	private static final float HEIGHT_RATIO = 0.60F;
	private static final float MIN_WIDTH = 240.0F;
	private static final float MIN_HEIGHT = 130.0F;
	private static final float MAX_WIDTH = 680.0F;
	private static final float MAX_HEIGHT = 440.0F;
	private static final String[] SORT_LABELS = {"Avg", "Total", "Calls", "Name"};
	private static final String[] MODE_LABELS = {"Live", "All"};

	private final Screen parent;
	private final ImString search = new ImString(64);
	private final ImInt sortIndex = new ImInt(0);
	private final ImInt modeIndex = new ImInt(0);
	private final ImBoolean freeze = new ImBoolean(false);
	private final ImBoolean open = new ImBoolean(true);
	private final ImBoolean showHistory = new ImBoolean(true);
	private final float[] minPercent = {0.0F};
	private final float[] history = new float[HISTORY_SIZE];
	private final float[] smoothHistory = new float[HISTORY_SIZE];
	private final ImVec2 graphSize = new ImVec2(-1.0F, 48.0F);
	private final List<ProfileRow> types = new ArrayList<>();
	private final List<ProfileRow> singles = new ArrayList<>();
	private final List<SubscriberRow> subscribers = new ArrayList<>();
	private final List<ProfileRow> typeFiltered = new ArrayList<>();
	private final List<ProfileRow> singleFiltered = new ArrayList<>();
	private final List<SubscriberRow> subscriberFiltered = new ArrayList<>();
	private final Map<String, TypeAccumulator> groups = new HashMap<>();
	private ImGuiListClipper clipper;
	private Mode mode = Mode.LIVE;
	private String query = "";
	private String remoteNoticeTitle;
	private String remoteNoticeMessage;
	private double tickMillis;
	private int sampleCount;
	private int entityCount;
	private int blockCount;
	private double totalMillis;
	private long totalCalls;
	private ProfileRow topType;
	private int historyCount;
	private float historyMinimum;
	private float historyMaximum;
	private float historyAverage;
	private float historyLatest;
	private long lastSnapshotAt;
	private long refreshedAt;
	private long copiedUntil;
	private int sortedKey = -1;
	private int sortedMode = -1;
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
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, this.width, this.height, SHADE);
		super.extractRenderState(graphics, mouseX, mouseY, delta);
	}

	public void buildImGui() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		refreshIfNeeded(minecraft, level);

		float menuWidth = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, this.width * WIDTH_RATIO));
		float menuHeight = Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, this.height * HEIGHT_RATIO));
		ImGui.setNextWindowPos((this.width - menuWidth) * 0.5F, (this.height - menuHeight) * 0.5F,
				ImGuiCond.FirstUseEver);
		ImGui.setNextWindowSize(menuWidth, menuHeight, ImGuiCond.FirstUseEver);
		ImGui.setNextWindowSizeConstraints(180.0F, 100.0F, 1400.0F, 1000.0F);
		ImGui.setNextWindowBgAlpha(0.86F);

		StringBuilder title = new StringBuilder(48);
		title.append("Exposed  ");

		if (this.tickMillis > 0.0) {
			title.append(Format.durationTick(this.tickMillis)).append("  ").append(Format.decimal1(tps())).append(" TPS");
		} else {
			title.append("waiting");
		}

		if (System.currentTimeMillis() < this.copiedUntil) {
			title.append("  copied");
		} else if (this.freeze.get()) {
			title.append("  frozen");
		}

		title.append("###exposed");
		this.open.set(true);

		if (!ImGui.begin(title.toString(), this.open, WINDOW_FLAGS)) {
			ImGui.end();

			if (!this.open.get()) {
				minecraft.execute(this::onClose);
			}

			return;
		}

		this.toolbar();
		// The toolbar may have changed the search box or the live/all-time mode.
		this.query = this.search.get().trim().toLowerCase(Locale.ROOT);
		this.mode = this.modeIndex.get() == 0 ? Mode.LIVE : Mode.ALL_TIME;
		this.sortRows();

		float footerHeight = ImGui.getFrameHeightWithSpacing();
		float bodyHeight = Math.max(ExposedImGui.scaled(32.0F), ImGui.getContentRegionAvailY() - footerHeight);
		ImGui.beginChild("##exposed-body", 0.0F, bodyHeight, false);
		this.tabs();
		ImGui.endChild();
		this.footer(minecraft);
		ImGui.end();

		if (!this.open.get()) {
			minecraft.execute(this::onClose);
		}
	}

	private void toolbar() {
		float available = ImGui.getContentRegionAvailX();
		float spacing = ExposedImGui.scaled(4.0F);
		float sortWidth = ExposedImGui.scaled(50.0F);
		float minWidth = ExposedImGui.scaled(44.0F);
		float modeWidth = ExposedImGui.scaled(46.0F);
		float searchWidth = Math.max(ExposedImGui.scaled(48.0F),
				available - sortWidth - minWidth - modeWidth - spacing * 3.0F);

		ImGui.setNextItemWidth(searchWidth);
		ImGui.inputTextWithHint("##exposed-search", "Search", this.search);

		ImGui.sameLine(0.0F, spacing);
		ImGui.setNextItemWidth(sortWidth);
		ImGui.combo("##exposed-sort", this.sortIndex, SORT_LABELS);
		ImGui.setItemTooltip("Sort: average, total, calls or name");

		ImGui.sameLine(0.0F, spacing);
		ImGui.setNextItemWidth(minWidth);
		ImGui.dragFloat("##exposed-min", this.minPercent, 0.1F, 0.0F, 50.0F, "%.0f%%");
		ImGui.setItemTooltip("Hide rows below this share of the tick");

		ImGui.sameLine(0.0F, spacing);
		ImGui.setNextItemWidth(modeWidth);
		ImGui.combo("##exposed-mode", this.modeIndex, MODE_LABELS);
		ImGui.setItemTooltip("Live per-tick averages or all-time averages");
	}

	private void footer(Minecraft minecraft) {
		ImGui.checkbox("Freeze", this.freeze);

		ImGui.sameLine(0.0F, 4.0F);
		if (ImGui.smallButton("Copy")) {
			this.copyReport(minecraft);
		}

		ImGui.sameLine(0.0F, 4.0F);
		if (ImGui.smallButton("Reset")) {
			this.reset();
		}

		ImGui.sameLine(0.0F, 4.0F);
		if (ImGui.smallButton("Settings")) {
			this.disposeClipper();
			minecraft.execute(() -> minecraft.setScreenAndShow(new ExposedConfigScreen(this)));
		}

		ImGui.sameLine(0.0F, 4.0F);
		if (ImGui.smallButton("Close")) {
			minecraft.execute(this::onClose);
		}
	}

	private void tabs() {
		if (!ImGui.beginTabBar("##exposed-tabs")) {
			return;
		}

		if (ImGui.beginTabItem("Overview")) {
			this.overviewTab();
			ImGui.endTabItem();
		}

		if (ImGui.beginTabItem("Types")) {
			this.tableTab("type", this.types, this.typeFiltered);
			ImGui.endTabItem();
		}

		if (ImGui.beginTabItem("Objects")) {
			this.tableTab("object", this.singles, this.singleFiltered);
			ImGui.endTabItem();
		}

		if (ImGui.beginTabItem("Threads")) {
			this.threadsTab();
			ImGui.endTabItem();
		}

		ImGui.endTabBar();
	}

	private void overviewTab() {
		boolean live = this.mode == Mode.LIVE;
		Minecraft minecraft = Minecraft.getInstance();
		this.remoteStatus(minecraft, minecraft.level);

		if (this.sampleCount == 0 && !Profiler.isEnabled()) {
			ImGui.textColored(1.0F, 0.82F, 0.30F, 1.0F, "Profiler is off - toggle it to start recording.");
		}
		if (this.remoteNoticeTitle != null) {
			ImGui.beginChild("##exposed-server-status", 0.0F, ExposedImGui.scaled(42.0F), true);
			ImGui.text("Information");
			ImGui.textWrapped(this.remoteNoticeMessage);
			ImGui.endChild();
		}

		ImGui.text(this.sampleCount + " objects   " + this.entityCount + " entities   " + this.blockCount
				+ " blocks");

		if (this.tickMillis > 0.0) {
			ImGui.textDisabled("Tick " + Format.durationTick(this.tickMillis) + "   " + Format.decimal1(tps())
					+ " TPS   profiled " + Format.durationTick(this.totalMillis) + "   "
					+ Format.count(this.totalCalls) + " calls");

			ImGui.sameLine(0.0F, ExposedImGui.scaled(8.0F));
		}

		ImGui.checkbox("History", this.showHistory);

		if (this.topType != null) {
			ImGui.text("Hottest: " + this.topType.label() + "   "
					+ Format.durationTick(this.topType.liveTickMillis()) + "   "
					+ Format.percent(this.topType.liveTickMillis(), this.tickMillis));
		}

		if (this.showHistory.get()) {
			if (this.historyCount > 0) {
				this.graphSize.x = -1.0F;
				this.graphSize.y = ExposedImGui.scaled(52.0F);
				ImGui.textDisabled("Tick time · last " + this.historyCount + " ticks");
				ImGui.sameLine(0.0F, ExposedImGui.scaled(8.0F));
				ImGui.text("Now " + Format.durationTick(this.historyLatest) + " ("
						+ Format.decimal1(tpsFor(this.historyLatest)) + " TPS)");
				ImGui.sameLine(0.0F, ExposedImGui.scaled(8.0F));
				ImGui.textDisabled("Avg " + Format.durationTick(this.historyAverage));
				ImGui.sameLine(0.0F, ExposedImGui.scaled(8.0F));
				ImGui.textDisabled("Low " + Format.durationTick(this.historyMinimum));
				ImGui.sameLine(0.0F, ExposedImGui.scaled(8.0F));
				ImGui.textDisabled("Peak " + Format.durationTick(this.historyMaximum));
				float maximum = Math.max(50.0F, this.historyMaximum * 1.1F);
				String overlay = this.tickMillis > 50.0 ? "Over 50 ms budget" : "Under 50 ms budget";
				ImGui.plotLines("##exposed-tick-graph", this.smoothHistory, this.historyCount, 0, overlay,
						0.0F, maximum, this.graphSize);
			} else {
				ImGui.textDisabled("No tick history yet.");
			}
		}

		List<ProfileRow> rows = this.filterProfiles(this.types, this.typeFiltered, live);
		List<SubscriberRow> events = this.filterSubscribers();
		float gap = ExposedImGui.scaled(6.0F);
		float available = ImGui.getContentRegionAvailX();

		if (available <= gap) {
			return;
		}

		float leftWidth = (available - gap) * 0.55F;
		float minimumPane = ExposedImGui.scaled(90.0F);

		if (leftWidth < minimumPane) {
			leftWidth = Math.max(ExposedImGui.scaled(60.0F), available - gap - minimumPane);
		}

		if (leftWidth > available - gap - minimumPane) {
			leftWidth = Math.max(ExposedImGui.scaled(60.0F), available - gap - minimumPane);
		}

		ImGui.beginChild("##exposed-overview-types", leftWidth, 0.0F, false);
		ImGui.textDisabled(live ? "Top types (live /t)" : "Top types (all /t)");
		this.profileTable("##exposed-top-types", rows, live, 0, true);
		ImGui.endChild();

		ImGui.sameLine(0.0F, gap);
		ImGui.beginChild("##exposed-overview-events", 0.0F, 0.0F, false);
		ImGui.textDisabled("Event handlers (/t)");
		this.subscriberTable("##exposed-overview-events-table", events, true, 0, true);
		ImGui.endChild();
	}

	private void tableTab(String kind, List<ProfileRow> source, List<ProfileRow> buffer) {
		boolean live = this.mode == Mode.LIVE;
		List<ProfileRow> rows = this.filterProfiles(source, buffer, live);
		ImGui.textDisabled(("type".equals(kind) ? "By type" : "Individual objects") + "   "
				+ Format.count(rows.size())
				+ (this.freeze.get() ? "   (frozen)" : ""));
		ImGui.beginChild("##exposed-" + kind + "-scroll", 0.0F, 0.0F, false);
		this.profileTable("##exposed-" + kind + "-table", rows, live, 0, true);
		ImGui.endChild();
	}

	private void threadsTab() {
		List<SubscriberRow> rows = this.filterSubscribers();
		ImGui.textDisabled("Event handlers   " + Format.count(rows.size()));
		ImGui.beginChild("##exposed-thread-scroll", 0.0F, 0.0F, false);
		this.subscriberTable("##exposed-thread-table", rows, false, 0, true);
		ImGui.endChild();
	}

	private void profileTable(String id, List<ProfileRow> rows, boolean live, int limit, boolean scrollable) {
		int count = limit > 0 ? Math.min(limit, rows.size()) : rows.size();
		boolean wide = ImGui.getContentRegionAvailX() >= ExposedImGui.scaled(290.0F);
		int columns = wide ? 6 : 4;
		int flags = scrollable ? TABLE_FLAGS : TABLE_FLAGS & ~ImGuiTableFlags.ScrollY;

		if (!ImGui.beginTable(id, columns, flags)) {
			return;
		}

		ImGui.tableSetupColumn("", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(14.0F));
		ImGui.tableSetupColumn("Type / name", ImGuiTableColumnFlags.WidthStretch, 1.0F);
		ImGui.tableSetupColumn("Avg/t", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(52.0F));

		if (wide) {
			ImGui.tableSetupColumn("Total", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(52.0F));
			ImGui.tableSetupColumn("%", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(34.0F));
			ImGui.tableSetupColumn("Calls", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(38.0F));
		} else {
			ImGui.tableSetupColumn("%", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(34.0F));
		}

		if (scrollable) {
			ImGui.tableSetupScrollFreeze(0, 1);
		}

		ImGui.tableHeadersRow();

		if (count == 0) {
			ImGui.tableNextRow();
			ImGui.tableNextColumn();
			ImGui.textDisabled(this.freeze.get() ? "No data in the frozen snapshot" : "No data");
			ImGui.endTable();
			return;
		}

		if (!scrollable) {
			for (int index = 0; index < count; index++) {
				this.profileRow(rows.get(index), live, wide);
			}
		} else {
			ImGuiListClipper listClipper = this.clipper();
			listClipper.begin(count);

			while (listClipper.step()) {
				for (int index = listClipper.getDisplayStart(); index < listClipper.getDisplayEnd(); index++) {
					this.profileRow(rows.get(index), live, wide);
				}
			}

			listClipper.end();
		}

		ImGui.endTable();
	}

	private void profileRow(ProfileRow row, boolean live, boolean wide) {
		double millis = live ? row.liveTickMillis() : row.allTimeTickMillis();
		int textColor = Heat.imgui(millis, 0xFF);
		int dimColor = Heat.imgui(millis, 0xB8);
		ImGui.pushStyleColor(ImGuiCol.Text, textColor);
		ImGui.pushStyleColor(ImGuiCol.TextDisabled, dimColor);
		ImGui.tableNextRow();
		ImGui.tableNextColumn();
		ImGui.textDisabled(row.block() ? "B" : "E");
		ImGui.tableNextColumn();
		ImGui.text(row.label());

		if (ImGui.isItemHovered()) {
			ImGui.setTooltip(row.label() + "\nLive: " + Format.durationTick(row.liveTickMillis())
					+ "\nAll time: " + Format.durationTick(row.allTimeTickMillis())
					+ "\nTotal: " + Format.duration(row.totalMillis())
					+ "\nCalls: " + row.calls());
		}

		ImGui.tableNextColumn();
		this.valueText(Format.durationTick(millis), millis);

		if (wide) {
			ImGui.tableNextColumn();
			ImGui.textDisabled(Format.duration(row.totalMillis()));
			ImGui.tableNextColumn();
			ImGui.textDisabled(Format.percent(millis, this.tickMillis));
			ImGui.tableNextColumn();
			ImGui.textDisabled(Format.count(row.calls()));
		} else {
			ImGui.tableNextColumn();
			ImGui.textDisabled(Format.percent(millis, this.tickMillis));
		}

		ImGui.popStyleColor(2);
	}

	private void subscriberTable(String id, List<SubscriberRow> rows, boolean compact, int limit,
			boolean scrollable) {
		int count = limit > 0 ? Math.min(limit, rows.size()) : rows.size();
		int columns = compact ? 2 : 3;
		int flags = scrollable ? TABLE_FLAGS : TABLE_FLAGS & ~ImGuiTableFlags.ScrollY;

		if (!ImGui.beginTable(id, columns, flags)) {
			return;
		}

		if (compact) {
			ImGui.tableSetupColumn("Handler", ImGuiTableColumnFlags.WidthStretch, 1.0F);
			ImGui.tableSetupColumn("ms/t", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(50.0F));
		} else {
			ImGui.tableSetupColumn("Mod / jar", ImGuiTableColumnFlags.WidthStretch, 1.0F);
			ImGui.tableSetupColumn("Subscriber", ImGuiTableColumnFlags.WidthStretch, 1.5F);
			ImGui.tableSetupColumn("ms/t", ImGuiTableColumnFlags.WidthFixed, ExposedImGui.scaled(50.0F));
		}

		if (scrollable) {
			ImGui.tableSetupScrollFreeze(0, 1);
		}

		ImGui.tableHeadersRow();

		if (count == 0) {
			ImGui.tableNextRow();
			ImGui.tableNextColumn();
			ImGui.textDisabled("Waiting for event handler data");
			ImGui.endTable();
			return;
		}

		if (!scrollable) {
			for (int index = 0; index < count; index++) {
				this.subscriberRow(rows.get(index), compact);
			}
		} else {
			ImGuiListClipper listClipper = this.clipper();
			listClipper.begin(count);

			while (listClipper.step()) {
				for (int index = listClipper.getDisplayStart(); index < listClipper.getDisplayEnd(); index++) {
					this.subscriberRow(rows.get(index), compact);
				}
			}

			listClipper.end();
		}

		ImGui.endTable();
	}

	private void subscriberRow(SubscriberRow row, boolean compact) {
		double millis = this.tickMillis * row.percent() / 100.0;
		ImGui.pushStyleColor(ImGuiCol.Text, Heat.imgui(millis, 0xFF));
		ImGui.pushStyleColor(ImGuiCol.TextDisabled, Heat.imgui(millis, 0xB8));
		ImGui.tableNextRow();

		if (compact) {
			ImGui.tableNextColumn();
			ImGui.text(shortHandler(row));

			if (ImGui.isItemHovered()) {
				ImGui.setTooltip(row.owner() + "  " + row.jar() + "\n" + row.className() + "." + row.methodName()
						+ "\n" + Format.decimal1(row.percent()) + "% of server thread samples");
			}

			ImGui.tableNextColumn();
			ImGui.text(Format.durationTick(millis));
		} else {
			ImGui.tableNextColumn();
			ImGui.text(row.owner() + "  " + row.jar());

			if (ImGui.isItemHovered()) {
				ImGui.setTooltip(row.owner() + "  " + row.jar());
			}

			ImGui.tableNextColumn();
			ImGui.text(row.className() + "." + row.methodName());

			if (ImGui.isItemHovered()) {
				ImGui.setTooltip(row.className() + "." + row.methodName() + "\n"
						+ Format.decimal1(row.percent()) + "% of server thread samples");
			}

			ImGui.tableNextColumn();
			ImGui.text(Format.durationTick(millis));
		}

		ImGui.popStyleColor(2);
	}

	private static String shortHandler(SubscriberRow row) {
		String className = row.className();
		int dot = className.lastIndexOf('.');
		String simple = dot >= 0 ? className.substring(dot + 1) : className;
		return simple + "." + row.methodName();
	}

	private void valueText(String text, double millis) {
		int rgb = Heat.rgb(millis);
		ImGui.textColored((rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F,
				(rgb & 0xFF) / 255.0F, 1.0F, text);
	}

	private List<ProfileRow> filterProfiles(List<ProfileRow> source, List<ProfileRow> buffer, boolean live) {
		double minimum = this.minPercent[0];

		if (this.query.isEmpty() && minimum <= 0.0) {
			return source;
		}

		buffer.clear();

		for (ProfileRow row : source) {
			if (!this.query.isEmpty() && !row.searchText().contains(this.query)) {
				continue;
			}

			if (minimum > 0.0) {
				double share = this.tickMillis > 0.0 ? (live ? row.liveMillis() : row.allTimeMillis())
						/ this.tickMillis * 100.0 : 0.0;

				if (share < minimum) {
					continue;
				}
			}

			buffer.add(row);
		}

		return buffer;
	}

	private List<SubscriberRow> filterSubscribers() {
		if (this.query.isEmpty()) {
			return this.subscribers;
		}

		this.subscriberFiltered.clear();

		for (SubscriberRow row : this.subscribers) {
			if (row.searchText().contains(this.query)) {
				this.subscriberFiltered.add(row);
			}
		}

		return this.subscriberFiltered;
	}

	private void sortRows() {
		int key = this.sortIndex.get();
		int modeOrdinal = this.mode.ordinal();

		if (key == this.sortedKey && modeOrdinal == this.sortedMode) {
			return;
		}

		this.sortedKey = key;
		this.sortedMode = modeOrdinal;
		boolean live = this.mode == Mode.LIVE;
		Comparator<ProfileRow> comparator = switch (key) {
			case 1 -> Comparator.comparingDouble(ProfileRow::totalMillis);
			case 2 -> Comparator.comparingLong(ProfileRow::calls);
			case 3 -> Comparator.comparing(ProfileRow::label, String.CASE_INSENSITIVE_ORDER);
			default -> live ? Comparator.comparingDouble(ProfileRow::liveMillis)
					: Comparator.comparingDouble(ProfileRow::allTimeMillis);
		};

		if (key != 3) {
			comparator = comparator.reversed();
		}

		this.types.sort(comparator);
		this.singles.sort(comparator);
	}

	private void refreshIfNeeded(Minecraft minecraft, ClientLevel level) {
		if (this.freeze.get()) {
			return;
		}

		long now = System.currentTimeMillis();

		if (this.refreshedAt != 0L && now - this.refreshedAt < REFRESH_MILLIS) {
			return;
		}

		this.refreshedAt = now;
		boolean singleplayer = minecraft.hasSingleplayerServer();
		RemoteFeed.Snapshot remote = !singleplayer && level != null
				? RemoteFeed.current(level.dimension())
				: null;
		List<Profiler.Sample> samples;

		if (singleplayer) {
			samples = level == null ? List.of() : Profiler.all(level.dimension());
			this.tickMillis = Profiler.averageTickMillis();
			this.historyCount = Profiler.fillTickHistory(this.history);
			this.updateHistorySummary();
		} else {
			samples = remote == null ? List.of() : remote.samples();
			this.tickMillis = remote == null ? 0.0 : remote.tickMillis();

			if (remote != null && remote.receivedAtMillis() != this.lastSnapshotAt) {
				this.lastSnapshotAt = remote.receivedAtMillis();

				if (this.tickMillis > 0.0) {
					this.pushHistory((float) this.tickMillis);
					this.updateHistorySummary();
				}
			}
		}

		this.groups.clear();
		this.types.clear();
		this.singles.clear();
		this.subscribers.clear();
		this.sampleCount = samples.size();
		this.entityCount = 0;
		this.blockCount = 0;
		this.totalMillis = 0.0;
		this.totalCalls = 0L;
		this.topType = null;

		for (Profiler.Sample sample : samples) {
			if (sample.block) {
				this.blockCount++;
			} else {
				this.entityCount++;
			}

			this.totalMillis += sample.averageMillis();
			this.totalCalls += sample.calls();
			this.singles.add(new ProfileRow(sample.name(),
					searchText(sample.name(), sample.type(), sample.block ? "block" : "entity"),
					sample.averageMillis(), sample.allTimePerTickMillis(), sample.totalMillis(), sample.calls(), sample.block));
			String key = (sample.block ? "B:" : "E:") + sample.type();
			this.groups.computeIfAbsent(key, ignored -> new TypeAccumulator(sample.type(), sample.block)).add(sample);
		}

		for (TypeAccumulator group : this.groups.values()) {
			ProfileRow row = group.row();
			this.types.add(row);

			if (this.topType == null || row.liveMillis() > this.topType.liveMillis()) {
				this.topType = row;
			}
		}

		if (singleplayer) {
			for (ServerThreadProfiler.Entry entry : ServerThreadProfiler.top(64)) {
				this.subscribers.add(new SubscriberRow(entry.owner(), entry.jar(), entry.className(),
						entry.methodName(), entry.percent(),
						searchText(entry.owner(), entry.jar(), entry.className(), entry.methodName())));
			}
		} else if (remote != null) {
			for (SnapshotPayload.Subscriber entry : remote.subscribers()) {
				this.subscribers.add(new SubscriberRow(entry.owner(), entry.jar(), entry.className(),
						entry.methodName(), entry.percent(),
						searchText(entry.owner(), entry.jar(), entry.className(), entry.methodName())));
			}
		}

		this.sortedKey = -1;
	}

	private void pushHistory(float value) {
		if (this.historyCount < HISTORY_SIZE) {
			this.history[this.historyCount++] = value;
		} else {
			System.arraycopy(this.history, 1, this.history, 0, HISTORY_SIZE - 1);
			this.history[HISTORY_SIZE - 1] = value;
		}
	}

	/** Cached at snapshot cadence so graph drawing does no per-frame smoothing work. */
	private void updateHistorySummary() {
		if (this.historyCount <= 0) {
			this.historyMinimum = 0.0F;
			this.historyMaximum = 0.0F;
			this.historyAverage = 0.0F;
			this.historyLatest = 0.0F;
			return;
		}

		float minimum = Float.MAX_VALUE;
		float maximum = 0.0F;
		float total = 0.0F;
		for (int i = 0; i < this.historyCount; i++) {
			float value = this.history[i];
			minimum = Math.min(minimum, value);
			maximum = Math.max(maximum, value);
			total += value;
		}
		this.historyMinimum = minimum;
		this.historyMaximum = maximum;
		this.historyAverage = total / this.historyCount;
		this.historyLatest = this.history[this.historyCount - 1];

		for (int i = 0; i < this.historyCount; i++) {
			float previous = this.history[Math.max(0, i - 1)];
			float next = this.history[Math.min(this.historyCount - 1, i + 1)];
			this.smoothHistory[i] = (previous + this.history[i] * 2.0F + next) * 0.25F;
		}
	}

	private void copyReport(Minecraft minecraft) {
		StringBuilder out = new StringBuilder(4096);
		out.append("Exposed report\n");
		out.append("Tick: ").append(Format.durationTick(this.tickMillis))
				.append("   TPS: ").append(Format.decimal1(tps())).append('\n');
		out.append("Objects: ").append(this.sampleCount).append(" (").append(this.entityCount)
				.append(" entities, ").append(this.blockCount).append(" blocks)\n");
		out.append("Profiled: ").append(Format.durationTick(this.totalMillis)).append("   Calls: ")
				.append(this.totalCalls).append('\n');
		out.append("\nBy type (live / all time / total / calls):\n");

		for (ProfileRow row : this.types) {
			out.append(row.block() ? "B " : "E ").append(row.label()).append(" | ")
					.append(Format.durationTick(row.liveMillis())).append(" | ")
					.append(Format.durationTick(row.allTimeMillis())).append(" | ")
					.append(Format.duration(row.totalMillis())).append(" | ")
					.append(row.calls()).append('\n');
		}

		out.append("\nObjects (live / all time / total / calls):\n");

		for (ProfileRow row : this.singles) {
			out.append(row.block() ? "B " : "E ").append(row.label()).append(" | ")
					.append(Format.durationTick(row.liveMillis())).append(" | ")
					.append(Format.durationTick(row.allTimeMillis())).append(" | ")
					.append(Format.duration(row.totalMillis())).append(" | ")
					.append(row.calls()).append('\n');
		}

		out.append("\nServer thread:\n");

		for (SubscriberRow row : this.subscribers) {
			out.append(row.owner()).append(' ').append(row.jar()).append(" | ")
					.append(row.className()).append('.').append(row.methodName()).append(" | ")
					.append(Format.decimal1(row.percent())).append("%\n");
		}

		minecraft.keyboardHandler.setClipboard(out.toString());
		this.copiedUntil = System.currentTimeMillis() + 1500L;
	}

	private void reset() {
		Profiler.clear();
		RemoteFeed.clear();
		this.freeze.set(false);
		this.types.clear();
		this.singles.clear();
		this.subscribers.clear();
		this.typeFiltered.clear();
		this.singleFiltered.clear();
		this.subscriberFiltered.clear();
		this.groups.clear();
		this.tickMillis = 0.0;
		this.sampleCount = 0;
		this.entityCount = 0;
		this.blockCount = 0;
		this.totalMillis = 0.0;
		this.totalCalls = 0L;
		this.topType = null;
		this.historyCount = 0;
		this.historyMinimum = 0.0F;
		this.historyMaximum = 0.0F;
		this.historyAverage = 0.0F;
		this.historyLatest = 0.0F;
		this.lastSnapshotAt = 0L;
		this.refreshedAt = 0L;
		this.copiedUntil = 0L;
		this.sortedKey = -1;
	}

	private double tps() {
		return this.tickMillis > 0.0 ? Math.min(20.0, 1000.0 / Math.max(50.0, this.tickMillis)) : 20.0;
	}

	private void remoteStatus(Minecraft minecraft, ClientLevel level) {
		this.remoteNoticeTitle = null;
		this.remoteNoticeMessage = null;
		if (minecraft.hasSingleplayerServer() || !Profiler.isEnabled() || level == null) {
			return;
		}
		if (!ClientPlayNetworking.canSend(SubscribePayload.TYPE)) {
			this.remoteNoticeTitle = "Information";
			this.remoteNoticeMessage = "Server data unavailable: This server does not advertise Exposed's profile channel. Exposed server component not installed.";
			return;
		}
		RemoteFeed.Snapshot snapshot = RemoteFeed.current(level.dimension());
		if (snapshot == null) {
			this.remoteNoticeTitle = "WAITING FOR SERVER DATA";
			this.remoteNoticeMessage = "The server supports Exposed, but no profile snapshot has arrived. Ask an administrator to check that profiling is enabled and access is allowed.";
		} else if (snapshot.samples().isEmpty()) {
			this.remoteNoticeTitle = "NO TICK SAMPLES YET";
			this.remoteNoticeMessage = "The server feed is connected, but it has not reported any entity or block-entity ticks yet.";
		}
	}

	private static double tpsFor(double millis) {
		return millis > 0.0 ? Math.min(20.0, 1000.0 / Math.max(50.0, millis)) : 20.0;
	}

	private ImGuiListClipper clipper() {
		if (this.clipper == null) {
			this.clipper = new ImGuiListClipper();
		}

		return this.clipper;
	}

	private static String searchText(String... parts) {
		StringBuilder out = new StringBuilder(48);

		for (String part : parts) {
			if (part != null && !part.isEmpty()) {
				out.append(part).append(' ');
			}
		}

		return out.toString().toLowerCase(Locale.ROOT);
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

	private void disposeClipper() {
		if (this.clipper != null) {
			this.clipper.destroy();
			this.clipper = null;
		}
	}

	@Override
	public void onClose() {
		this.disposeClipper();

		if (this.parent == null) {
			this.minecraft.gui.setScreen(null);
		} else {
			this.minecraft.setScreenAndShow(this.parent);
		}
	}

	@Override
	public boolean isPauseScreen() {
		// Keep the world ticking so the numbers and the tick graph stay live while
		// the analysis menu is open.
		return false;
	}

	public float cursorX() {
		return (float) this.cursorX;
	}

	public float cursorY() {
		return (float) this.cursorY;
	}

	private enum Mode {
		LIVE,
		ALL_TIME
	}

	private record ProfileRow(String label, String searchText, double liveMillis, double allTimeMillis,
			double totalMillis, long calls, boolean block) {
		private double liveTickMillis() { return this.liveMillis; }
		private double allTimeTickMillis() { return this.allTimeMillis; }
	}

	private record SubscriberRow(String owner, String jar, String className, String methodName, double percent,
			String searchText) {
	}

	private static final class TypeAccumulator {
		private final String type;
		private final boolean block;
		private double live;
		private double allTime;
		private double totalMillis;
		private long calls;

		private TypeAccumulator(String type, boolean block) {
			this.type = type;
			this.block = block;
		}

		private void add(Profiler.Sample sample) {
			this.live += sample.averageMillis();
			this.allTime += sample.allTimePerTickMillis();
			this.totalMillis += sample.totalMillis();
			this.calls += sample.calls();
		}

		private ProfileRow row() {
			// Aggregate cumulative cost divided by observed server ticks is a /t
			// rate, matching the live value and the world heat classification.
			return new ProfileRow(this.type, searchText(this.type, this.block ? "block" : "entity"), this.live,
					this.allTime, this.totalMillis, this.calls, this.block);
		}
	}
}
