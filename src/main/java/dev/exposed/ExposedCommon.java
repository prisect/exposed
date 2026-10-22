package dev.exposed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.exposed.network.SnapshotPayload;
import dev.exposed.network.SubscribePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ExposedCommon implements ModInitializer {
	public static final String MOD_ID = "exposed";
	private static final int REMOTE_LIMIT = 512;

	private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
	private static long tickCounter;

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(SubscribePayload.TYPE, SubscribePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SnapshotPayload.TYPE, SnapshotPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SubscribePayload.TYPE, (payload, context) -> {
			UUID id = context.player().getUUID();
			context.server().execute(() -> {
				if (payload.enabled()) {
					SUBSCRIBERS.add(id);
				} else {
					SUBSCRIBERS.remove(id);
				}

				Profiler.setServerRecording(!SUBSCRIBERS.isEmpty());
			});
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (SUBSCRIBERS.remove(handler.player.getUUID())) {
				Profiler.setServerRecording(!SUBSCRIBERS.isEmpty());
			}
		});

		ServerTickEvents.START_SERVER_TICK.register(server -> Profiler.onServerTickStart());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Profiler.onServerTickEnd();

			if (++tickCounter % 4L == 0L) {
				broadcast(server);
			}
		});
	}

	private static void broadcast(MinecraftServer server) {
		if (SUBSCRIBERS.isEmpty()) {
			return;
		}

		for (UUID id : List.copyOf(SUBSCRIBERS)) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);

			if (player == null) {
				SUBSCRIBERS.remove(id);
				continue;
			}

			if (!ServerPlayNetworking.canSend(player, SnapshotPayload.TYPE)) {
				continue;
			}

			ServerLevel level = player.level();
			Identifier dimension = level.dimension().identifier();
			Map<String, Profiler.Sample> merged = new LinkedHashMap<>();

			for (Profiler.Sample sample : Profiler.top(level.dimension(), REMOTE_LIMIT / 2)) {
				merged.put(sampleKey(sample), sample);
			}

			for (Profiler.Sample sample : Profiler.topByTotal(level.dimension(), REMOTE_LIMIT / 2)) {
				merged.putIfAbsent(sampleKey(sample), sample);
			}

			List<SnapshotPayload.Entry> entries = new ArrayList<>(merged.size());

			for (Profiler.Sample sample : merged.values()) {
				entries.add(new SnapshotPayload.Entry(
						trim(sample.name(), 64),
						trim(sample.type(), 64),
						sample.averageMillis(),
						sample.totalMillis(),
						sample.calls(),
						sample.block,
						sample.pos,
						sample.entityId
				));
			}

			List<SnapshotPayload.Subscriber> subscribers = new ArrayList<>();
			for (ServerThreadProfiler.Entry entry : ServerThreadProfiler.top(32)) {
				subscribers.add(new SnapshotPayload.Subscriber(
						trim(entry.owner(), 64),
						trim(entry.jar(), 64),
						trim(entry.className(), 128),
						trim(entry.methodName(), 64),
						entry.percent()
				));
			}

			ServerPlayNetworking.send(player, new SnapshotPayload(
					dimension,
					(float) Profiler.averageTickMillis(),
					entries,
					subscribers
			));
		}

		if (SUBSCRIBERS.isEmpty()) {
			Profiler.setServerRecording(false);
		}
	}

	private static String sampleKey(Profiler.Sample sample) {
		return sample.block ? "B" + sample.pos : "E" + sample.entityId;
	}

	private static String trim(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "~";
	}
}
