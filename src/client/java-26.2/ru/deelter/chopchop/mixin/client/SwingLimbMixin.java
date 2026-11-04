package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.Limbs;

/**
 * A swing of a main hand that is gone: the other hand swings instead (the game tells the others which hand swung),
 * or with both hands gone a leg kicks. 26.2: the swing names only the hand.
 */
@Mixin(LocalPlayer.class)
public abstract class SwingLimbMixin {
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"), cancellable = true)
	private void chopchop$limb(InteractionHand hand, CallbackInfo ci) {
		if (hand != InteractionHand.MAIN_HAND || !Limbs.handGone(InteractionHand.MAIN_HAND)) return;
		ci.cancel();
		if (Limbs.offHandDigs()) ((LocalPlayer) (Object) this).swing(InteractionHand.OFF_HAND);
		else Limbs.kick();
	}
}
