package ru.deelter.chopchop.mixin.client;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.Corpses;

/**
 * A body that stays on the ground does not go up in the puff of smoke of a vanilla death, and is not in the way.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEventMixin {
	/**
	 * {@code EntityEvent.POOF}
	 */
	private static final byte POOF = 60;

	/**
	 * A body on the ground is scenery: the crosshair goes through it (blocks behind it can be broken, mobs behind it
	 * hit) and it pushes nobody.
	 */
	@Inject(method = {"isPickable", "isPushable"}, at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$scenery(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (Corpses.get(((LivingEntity) (Object) this).getId()) != null) cir.setReturnValue(false);
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$noPoof(byte event, CallbackInfo ci) {
		if (event == POOF && Corpses.get(((LivingEntity) (Object) this).getId()) != null) ci.cancel();
	}
}
