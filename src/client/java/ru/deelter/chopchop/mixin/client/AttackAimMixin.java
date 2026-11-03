package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.ModelPick;

/**
 * Right before the attack packet: where on the drawn body the blow lands, so the server can use it
 * (see {@link ModelPick}). It goes out first, the server reads both in the order they were sent.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class AttackAimMixin {
	@Inject(method = "attack", at = @At("HEAD"), require = 0)
	private void chopchop$aim(Player player, Entity target, CallbackInfo ci) {
		ModelPick.beforeAttack(target);
	}
}
