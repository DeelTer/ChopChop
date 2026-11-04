package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.Limbs;

/**
 * A hand that is gone does nothing: what it would use, eat, place or swing at is passed on to the other hand (the
 * game tries both hands in turn), an attack needs the main hand. Digging goes on as long as a limb is left.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void chopchop$useItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		if (Limbs.isLocal(player) && Limbs.handGone(hand)) cir.setReturnValue(InteractionResult.PASS);
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void chopchop$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (Limbs.isLocal(player) && Limbs.handGone(hand)) cir.setReturnValue(InteractionResult.PASS);
	}

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void chopchop$interact(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
								   CallbackInfoReturnable<InteractionResult> cir) {
		if (Limbs.isLocal(player) && Limbs.handGone(hand)) cir.setReturnValue(InteractionResult.PASS);
	}

	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void chopchop$attack(Player player, Entity target, CallbackInfo ci) {
		if (Limbs.isLocal(player) && Limbs.handGone(InteractionHand.MAIN_HAND)) ci.cancel();
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void chopchop$startDigging(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (!Limbs.canDig()) cir.setReturnValue(false);
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void chopchop$keepDigging(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
		if (!Limbs.canDig()) cir.setReturnValue(false);
	}
}
