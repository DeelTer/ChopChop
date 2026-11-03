package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.Corpses;

/**
 * A dead mob the server removed stays in the client world as a body for a while (see {@link Corpses}). Without
 * this hook (another version of the game) bodies simply go the vanilla way.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "removeEntity", at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$keepBody(int id, Entity.RemovalReason reason, CallbackInfo ci) {
		if (Corpses.holds(id)) ci.cancel();
	}
}
