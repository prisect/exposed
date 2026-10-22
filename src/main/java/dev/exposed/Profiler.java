package dev.exposed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class Profiler {
	private static final long STALE_MILLIS = 3_000L;
	private static final long FORGET_MILLIS = 10_000L;

	private record Key(boolean block, ResourceKey<Level> dimension, long pos, int entityId) {
	}

	public static final class Sample {
		public final boolean block;
		public final ResourceKey<Level> dimension;
		public final long pos;
		public final int entityId;
		private final String name;
		private final String type;
		private volatile long lastSeenMillis;
		private volatile double averageNanos;
		private volatile long totalNanos;
		private volatile long calls;
		private int samples;

		private Sample(String name, String type, boolean block, ResourceKey<Level> dimension, long pos, int entityId) {
			this.name = name;
			this.type = type;
			this.block = block;
			this.dimension = dimension;
			this.pos = pos;
			this.entityId = entityId;
		}

		private synchronized void record(long nanos) {
			this.averageNanos = this.samples == 0 ? nanos : this.averageNanos * 0.9 + nanos * 0.1;
			this.totalNanos += nanos;
			this.calls++;
			this.samples++;
			this.lastSeenMillis = System.currentTimeMillis();
		}

		private boolean isFresh(long now) {
			return now - this.lastSeenMillis < STALE_MILLIS;
		}

		public String name() {
			return this.name;
		}

		public String type() {
			return this.type;
		}

		public double averageMillis() {
			return this.averageNanos / 1_000_000.0;
		}

		public double allTimeMillis() {
			long recorded = this.calls;
			return recorded == 0L ? 0.0 : this.totalNanos / (double) recorded / 1_000_000.0;
		}

		public double totalMillis() {
			return this.totalNanos / 1_000_000.0;
		}

		public long calls() {
			return this.calls;
		}
	}

	private static final Map<Key, Sample> SAMPLES = new ConcurrentHashMap<>();

	private static volatile boolean enabled;
	private static volatile boolean serverRecording;
	private static volatile boolean insideTick;
	private static volatile long tickStartNanos;
	private static volatile double lastTickMillis;
	private static volatile double averageTickMillis;
	private static volatile long ticks;

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean isRecording() {
		return enabled || serverRecording;
	}

	public static void setServerRecording(boolean value) {
		if (serverRecording == value) {
			return;
		}

		serverRecording = value;

		if (!enabled) {
			reset();
		}
	}

	public static void toggle() {
		setEnabled(!enabled);
	}

	public static void setEnabled(boolean value) {
		if (enabled == value) {
			return;
		}

		enabled = value;

		if (value || !serverRecording) {
			reset();
		}
	}

	public static boolean insideTick() {
		return insideTick;
	}

	public static void onServerTickStart() {
		if (!isRecording()) {
			return;
		}

		tickStartNanos = System.nanoTime();
		insideTick = true;
		ServerThreadProfiler.start();
	}

	public static void onServerTickEnd() {
		insideTick = false;

		if (!isRecording()) {
			return;
		}

		long start = tickStartNanos;
		if (start == 0L) {
			return;
		}

		tickStartNanos = 0L;
		double millis = (System.nanoTime() - start) / 1_000_000.0;
		lastTickMillis = millis;
		averageTickMillis = ticks == 0L ? millis : averageTickMillis * 0.9 + millis * 0.1;
		ticks++;

		if ((ticks & 63L) == 0L) {
			prune();
		}
	}

	public static void recordBlockEntity(ServerLevel level, BlockPos pos, String type, long nanos) {
		if (!isRecording()) {
			return;
		}

		ResourceKey<Level> dimension = level.dimension();
		Key key = new Key(true, dimension, pos.asLong(), 0);
		Sample sample = SAMPLES.get(key);

		if (sample == null) {
			String name = pretty(type);
			sample = new Sample(name, name, true, dimension, pos.asLong(), 0);
			Sample existing = SAMPLES.putIfAbsent(key, sample);
			if (existing != null) {
				sample = existing;
			}
		}

		sample.record(nanos);
	}

	public static void recordEntity(ServerLevel level, Entity entity, long nanos) {
		if (!isRecording() || entity instanceof Player) {
			return;
		}

		ResourceKey<Level> dimension = level.dimension();
		Key key = new Key(false, dimension, 0L, entity.getId());
		Sample sample = SAMPLES.get(key);

		if (sample == null) {
			String type = pretty(entity.getType().toShortString());
			String name = entity.getName().getString();
			if (name.isBlank() || name.equals(entity.getType().toShortString())) {
				name = type;
			}

			sample = new Sample(name, type, false, dimension, 0L, entity.getId());
			Sample existing = SAMPLES.putIfAbsent(key, sample);
			if (existing != null) {
				sample = existing;
			}
		}

		sample.record(nanos);
	}

	public static List<Sample> all(ResourceKey<Level> dimension) {
		long now = System.currentTimeMillis();
		List<Sample> result = new ArrayList<>();

		for (Sample sample : SAMPLES.values()) {
			if (sample.dimension.equals(dimension) && sample.isFresh(now)) {
				result.add(sample);
			}
		}

		result.sort((a, b) -> Double.compare(b.averageMillis(), a.averageMillis()));
		return result;
	}

	public static List<Sample> top(ResourceKey<Level> dimension, int limit) {
		List<Sample> result = all(dimension);

		if (result.size() > limit) {
			return new ArrayList<>(result.subList(0, limit));
		}

		return result;
	}

	public static List<Sample> topByTotal(ResourceKey<Level> dimension, int limit) {
		List<Sample> result = all(dimension);
		result.sort((a, b) -> Double.compare(b.totalMillis(), a.totalMillis()));

		if (result.size() > limit) {
			return new ArrayList<>(result.subList(0, limit));
		}

		return result;
	}

	public static double lastTickMillis() {
		return lastTickMillis;
	}

	public static double averageTickMillis() {
		return averageTickMillis;
	}

	public static long ticks() {
		return ticks;
	}

	static Sample remoteSample(String name, String type, boolean block, ResourceKey<Level> dimension, long pos,
			int entityId, double millis, double totalMillis, long calls) {
		Sample sample = new Sample(name, type, block, dimension, pos, entityId);
		sample.averageNanos = Math.max(0.0, millis) * 1_000_000.0;
		sample.totalNanos = Math.max(0.0, totalMillis) > 0.0
				? Math.round(totalMillis * 1_000_000.0)
				: Math.round(sample.averageNanos * Math.max(1L, calls));
		sample.calls = Math.max(1L, calls);
		sample.lastSeenMillis = System.currentTimeMillis();
		return sample;
	}

	private static void reset() {
		SAMPLES.clear();
		ServerThreadProfiler.stop();
		ServerThreadProfiler.reset();
		insideTick = false;
		tickStartNanos = 0L;
		lastTickMillis = 0.0;
		averageTickMillis = 0.0;
		ticks = 0L;
	}

	private static void prune() {
		long now = System.currentTimeMillis();
		SAMPLES.entrySet().removeIf(entry -> now - entry.getValue().lastSeenMillis > FORGET_MILLIS);
	}

	private static String pretty(String id) {
		int colon = id.indexOf(':');
		if (colon >= 0 && colon + 1 < id.length()) {
			id = id.substring(colon + 1);
		}

		return id.replace('_', ' ');
	}
}
