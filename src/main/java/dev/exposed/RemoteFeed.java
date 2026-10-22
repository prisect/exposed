package dev.exposed;

import java.util.ArrayList;
import java.util.List;

import dev.exposed.network.SnapshotPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class RemoteFeed {
	private static final long STALE_MILLIS = 10_000L;

	private static volatile Snapshot snapshot;

	public static void accept(SnapshotPayload payload) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null) {
			return;
		}

		ResourceKey<Level> dimension = level.dimension();

		if (!dimension.identifier().equals(payload.dimension())) {
			return;
		}

		List<Profiler.Sample> samples = new ArrayList<>(payload.entries().size());

		for (SnapshotPayload.Entry entry : payload.entries()) {
			samples.add(Profiler.remoteSample(
					entry.name(),
					entry.type(),
					entry.block(),
					dimension,
					entry.pos(),
					entry.entityId(),
					entry.millis(),
					entry.totalMillis(),
					entry.calls()
			));
		}

		snapshot = new Snapshot(payload.dimension(), payload.tickMillis(), List.copyOf(samples), payload.subscribers(),
				System.currentTimeMillis());
	}

	public static Snapshot current(ResourceKey<Level> dimension) {
		Snapshot current = snapshot;

		if (current == null || dimension == null) {
			return null;
		}

		if (!current.dimension().equals(dimension.identifier())) {
			return null;
		}

		if (System.currentTimeMillis() - current.receivedAtMillis() > STALE_MILLIS) {
			return null;
		}

		return current;
	}

	public static void clear() {
		snapshot = null;
	}

	public record Snapshot(Identifier dimension, double tickMillis, List<Profiler.Sample> samples,
			List<SnapshotPayload.Subscriber> subscribers, long receivedAtMillis) {
	}
}
