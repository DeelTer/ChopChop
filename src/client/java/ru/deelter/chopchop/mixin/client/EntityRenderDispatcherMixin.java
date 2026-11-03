package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.Corpses;

/**
 * A dead player whose body is drawn as a copy (see {@link Corpses}) is not drawn itself while it lies dying.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends Entity> void chopchop$hideDead(E entity, Frustum frustum, double camX, double camY, double camZ,
													  CallbackInfoReturnable<Boolean> cir) {
		if (Corpses.hides(entity)) cir.setReturnValue(false);
	}
}
