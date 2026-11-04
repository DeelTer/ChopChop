package ru.deelter.chopchop.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.Limbs;

/**
 * With a leg gone the local player does not sprint. Sprinting is the client's call, so this is where it stops.
 */
@Mixin(Entity.class)
public abstract class SprintMixin {
	@Inject(method = "setSprinting", at = @At("HEAD"), cancellable = true)
	private void chopchop$limp(boolean sprinting, CallbackInfo ci) {
		if (sprinting && Limbs.isLocal((Entity) (Object) this) && Limbs.limping()) ci.cancel();
	}
}
