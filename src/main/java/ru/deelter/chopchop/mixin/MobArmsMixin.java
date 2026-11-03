package ru.deelter.chopchop.mixin;

import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.server.ChopServer;

/**
 * A mob without arms hits nothing, and a mob without the hand that holds things picks nothing up again: a skeleton
 * that lost its bow arm does not take the bow back from the ground. Ranged attacks stop on their own, the item of
 * a cut off hand is taken away when it comes off (see {@code Consequences.disarm}).
 * <p>
 * The handlers take no arguments of the target methods, so they survive small changes of their signatures.
 */
@Mixin(Mob.class)
public abstract class MobArmsMixin {
	@Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$noArms(CallbackInfoReturnable<Boolean> cir) {
		if (ChopServer.armless((Mob) (Object) this)) cir.setReturnValue(false);
	}

	@Inject(method = "wantsToPickUp", at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$noHand(CallbackInfoReturnable<Boolean> cir) {
		if (ChopServer.mainHandless((Mob) (Object) this)) cir.setReturnValue(false);
	}
}
