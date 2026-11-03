package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.render.GoreRender;

/**
 * A cube of a wounded part is drawn by {@link GoreRender}, with holes; every other cube as usual.
 */
@Mixin(ModelPart.Cube.class)
public abstract class CubeMixin {
	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void chopchop$compile(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color, CallbackInfo ci) {
		if (GoreRender.active() && GoreRender.compile((ModelPart.Cube) (Object) this, pose, builder, lightCoords, overlayCoords, color)) {
			ci.cancel();
		}
	}
}
