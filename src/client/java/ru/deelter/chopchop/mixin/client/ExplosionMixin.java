package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.Corpses;
import ru.deelter.chopchop.client.render.Debris;

/**
 * A blast near pieces and bodies on the ground throws them about again. The packet is handled on the network
 * thread first and then again on the client thread; only the second time counts.
 */
@Mixin(ClientPacketListener.class)
public abstract class ExplosionMixin {
	@Inject(method = "handleExplode", at = @At("TAIL"), require = 0)
	private void chopchop$blast(ClientboundExplodePacket packet, CallbackInfo ci) {
		if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) return;
		Debris.blast(packet.center());
		Corpses.blast(packet.center());
	}
}
