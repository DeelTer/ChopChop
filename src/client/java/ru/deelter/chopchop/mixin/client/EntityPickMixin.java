package ru.deelter.chopchop.mixin.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.Corpses;

/**
 * A body on the ground is not picked by the crosshair, where the game asks it of {@code Entity} itself; the same
 * hook on {@code LivingEntity} covers a version where it is overridden there. A click at a body cuts it instead,
 * see {@code ModelPick}.
 */
@Mixin(Entity.class)
public abstract class EntityPickMixin {
	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$notABody(CallbackInfoReturnable<Boolean> cir) {
		Entity self = (Entity) (Object) this;
		if (self instanceof LivingEntity && Corpses.get(self.getId()) != null) cir.setReturnValue(false);
	}
}
