package ru.deelter.chopchop.mixin.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.Limbs;

/**
 * A swing of a main hand that is gone: the other hand swings instead (the game tells the others which hand swung),
 * or with both hands gone a leg kicks. 26.3: the swing names its animation, and the local player's is the one of
 * LivingEntity.
 */
@Mixin(LivingEntity.class)
public abstract class SwingLimbMixin {
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/component/SwingAnimation;Z)Z",
			at = @At("HEAD"), cancellable = true)
	private void chopchop$limb(InteractionHand hand, SwingAnimation animation, boolean updateSelf, CallbackInfoReturnable<Boolean> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (hand != InteractionHand.MAIN_HAND || !Limbs.isLocal(self) || !Limbs.handGone(InteractionHand.MAIN_HAND)) return;
		if (Limbs.offHandDigs()) {
			cir.setReturnValue(self.swing(InteractionHand.OFF_HAND, animation, updateSelf));
		} else {
			Limbs.kick();
			cir.setReturnValue(false);
		}
	}
}
