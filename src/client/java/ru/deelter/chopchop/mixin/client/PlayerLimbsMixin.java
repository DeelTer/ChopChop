package ru.deelter.chopchop.mixin.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.Limbs;

/**
 * A tool in a hand that is gone does not dig: the local player digs as fast as with a bare hand. Only the own
 * client is slowed; the server is more lenient, so it never refuses the block.
 */
@Mixin(Player.class)
public abstract class PlayerLimbsMixin {
	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void chopchop$bareHanded(BlockState state, CallbackInfoReturnable<Float> cir) {
		Player self = (Player) (Object) this;
		if (!Limbs.isLocal(self) || !Limbs.handGone(InteractionHand.MAIN_HAND)) return;
		float tool = self.getMainHandItem().getDestroySpeed(state);
		if (tool > 1) cir.setReturnValue(cir.getReturnValueF() / tool);
	}
}
