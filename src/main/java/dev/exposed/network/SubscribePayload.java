package dev.exposed.network;

import dev.exposed.ExposedCommon;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SubscribePayload(boolean enabled) implements CustomPacketPayload {
	public static final Type<SubscribePayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath(ExposedCommon.MOD_ID, "subscribe")
	);

	public static final StreamCodec<FriendlyByteBuf, SubscribePayload> CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeBoolean(payload.enabled()),
			buf -> new SubscribePayload(buf.readBoolean())
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
