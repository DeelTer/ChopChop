package ru.deelter.chopchop.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.server.ChopServer;

/**
 * Wounds come only from damage the game really dealt: {@code hurtServer} returned true (not cancelled, not fully
 * blocked by a shield, not swallowed by invulnerability frames) and health or absorption actually went down (armor
 * and enchantments left something). Anything else, a server plugin or mod that cancels damage included, leaves the
 * body as it was.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void chopchop$before(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir,
								 @Share("health") LocalFloatRef health) {
		LivingEntity self = (LivingEntity) (Object) this;
		health.set(self.getHealth() + self.getAbsorptionAmount());
	}

	@Inject(method = "hurtServer", at = @At("RETURN"))
	private void chopchop$after(ServerLevel level, DamageSource source, float damage, CallbackInfoReturnable<Boolean> cir,
								@Share("health") LocalFloatRef health) {
		if (!cir.getReturnValueZ()) return;
		LivingEntity self = (LivingEntity) (Object) this;
		float lost = health.get() - (self.getHealth() + self.getAbsorptionAmount());
		if (lost > 0) ChopServer.onDamaged(self, source, lost);
	}
}
