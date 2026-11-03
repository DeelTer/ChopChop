package ru.deelter.chopchop.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import ru.deelter.chopchop.core.net.Protocol;

/**
 * A message of {@link Protocol} on its plugin channel. The bytes go over the wire as they are, with nothing around
 * them: that is what a Paper plugin gets and sends.
 */
public record ChopPayload(byte[] data) implements CustomPacketPayload {
	public static final Type<ChopPayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));
	public static final StreamCodec<RegistryFriendlyByteBuf, ChopPayload> CODEC = StreamCodec.of(
			(buffer, payload) -> buffer.writeBytes(payload.data),
			buffer -> {
				int length = buffer.readableBytes();
				if (length > Protocol.MAX_MESSAGE) throw new IllegalArgumentException("chopchop message too big: " + length);
				final byte[] data = new byte[length];
				buffer.readBytes(data);
				return new ChopPayload(data);
			});

	@Override
	public Type<ChopPayload> type() {
		return TYPE;
	}
}
