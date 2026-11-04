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
 * A dead player whose body is drawn as a copy (see {@link Corpses}) is not drawn itself while it lies dying, and a
 * body on the ground is not culled by its tiny box. 26.3 passes the partial tick too.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends Entity> void chopchop$hideDead(E entity, Frustum frustum, double camX, double camY, double camZ, float partial,
													  CallbackInfoReturnable<Boolean> cir) {
		if (Corpses.hides(entity)) {
			cir.setReturnValue(false);
			return;
		}
		// a body on the ground is long, its box (the dying pose's, 0.2 blocks at the feet) is not: culled by that box it
		// blinks out whenever its feet leave the screen. It is drawn whenever it is near enough
		if (Corpses.get(entity.getId()) != null) {
			double dx = entity.getX() - camX, dy = entity.getY() - camY, dz = entity.getZ() - camZ;
			cir.setReturnValue(dx * dx + dy * dy + dz * dz < 96 * 96);
		}
	}
}
