package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.render.ChopRenderState;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * A pumpkin, a skull or a banner on a head that came off is not left floating over the neck.
 */
@Mixin(CustomHeadLayer.class)
public abstract class HeadlessHeadLayerMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V",
			at = @At("HEAD"), cancellable = true)
	private void chopchop$headless(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, LivingEntityRenderState state,
								   float yRot, float xRot, CallbackInfo ci) {
		WoundState wounds = ((ChopRenderState) state).chopchop$wounds();
		if (wounds != null && wounds.headless()) ci.cancel();
	}
}
