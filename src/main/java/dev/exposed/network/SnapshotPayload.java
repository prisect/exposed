package dev.exposed.network;

import java.util.ArrayList;
import java.util.List;

import dev.exposed.ExposedCommon;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SnapshotPayload(Identifier dimension, float tickMillis, List<Entry> entries, List<Subscriber> subscribers)
		implements CustomPacketPayload {
	private static final int MAX_ENTRIES = 512;
	private static final int MAX_SUBSCRIBERS = 64;

	public static final Type<SnapshotPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(ExposedCommon.MOD_ID, "snapshot")
	);

	public static final StreamCodec<FriendlyByteBuf, SnapshotPayload> CODEC = StreamCodec.of(
			SnapshotPayload::write,
			SnapshotPayload::read
	);

	public SnapshotPayload {
		entries = List.copyOf(entries);
		subscribers = List.copyOf(subscribers);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	private static void write(FriendlyByteBuf buf, SnapshotPayload payload) {
		buf.writeIdentifier(payload.dimension);
		buf.writeFloat(payload.tickMillis);
		buf.writeVarInt(payload.entries.size());

		for (Entry entry : payload.entries) {
			buf.writeUtf(entry.name(), 128);
			buf.writeUtf(entry.type(), 128);
			buf.writeFloat((float) entry.millis());
			buf.writeFloat((float) entry.totalMillis());
			buf.writeVarLong(entry.calls());
			buf.writeBoolean(entry.block());
			buf.writeLong(entry.pos());
			buf.writeVarInt(entry.entityId());
		}

		buf.writeVarInt(payload.subscribers.size());

		for (Subscriber subscriber : payload.subscribers) {
			buf.writeUtf(subscriber.owner(), 128);
			buf.writeUtf(subscriber.jar(), 128);
			buf.writeUtf(subscriber.className(), 192);
			buf.writeUtf(subscriber.methodName(), 96);
			buf.writeFloat((float) subscriber.percent());
		}
	}

	private static SnapshotPayload read(FriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		float tickMillis = buf.readFloat();
		int count = buf.readVarInt();

		if (count < 0 || count > MAX_ENTRIES) {
			throw new DecoderException("Invalid Exposed sample count: " + count);
		}

		List<Entry> entries = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			entries.add(new Entry(
					buf.readUtf(128),
					buf.readUtf(128),
					buf.readFloat(),
					buf.readFloat(),
					buf.readVarLong(),
					buf.readBoolean(),
					buf.readLong(),
					buf.readVarInt()
			));
		}

		int subscriberCount = buf.readVarInt();

		if (subscriberCount < 0 || subscriberCount > MAX_SUBSCRIBERS) {
			throw new DecoderException("Invalid Exposed subscriber count: " + subscriberCount);
		}

		List<Subscriber> subscribers = new ArrayList<>(subscriberCount);

		for (int i = 0; i < subscriberCount; i++) {
			subscribers.add(new Subscriber(
					buf.readUtf(128),
					buf.readUtf(128),
					buf.readUtf(192),
					buf.readUtf(96),
					buf.readFloat()
			));
		}

		return new SnapshotPayload(dimension, tickMillis, entries, subscribers);
	}

	public record Entry(String name, String type, double millis, double totalMillis, long calls, boolean block, long pos,
			int entityId) {
	}

	public record Subscriber(String owner, String jar, String className, String methodName, double percent) {
	}
}
