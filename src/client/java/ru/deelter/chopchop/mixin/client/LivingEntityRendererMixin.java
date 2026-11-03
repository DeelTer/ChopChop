package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import ru.deelter.chopchop.client.ChopChopClient;
import ru.deelter.chopchop.client.ClientWounds;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.client.render.ChopRenderState;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Takes the wounds of an entity into its render state, and notes the model and camera of its draw.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"))
	private void chopchop$extract(LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
		ClientConfig config = ClientConfig.get();
		// far away only small wounds are left out (see ModelFeatureRendererMixin): what is cut off stays off
		WoundState wounds = config.isEnabled() ? ClientWounds.get(entity.getId()) : null;
		((ChopRenderState) state).chopchop$set(entity.getId(), wounds);
	}

	/**
	 * A mob without legs lies down and crawls, turned exactly as the server finds hits on it ({@link BodyPose}).
	 * Players crawl in the vanilla pose instead, which the game draws itself.
	 */
	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
			at = @At("TAIL"))
	private void chopchop$crawl(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		if (state instanceof AvatarRenderState || !ChopChopClient.serverSpeaks()) return;
		WoundState wounds = ((ChopRenderState) state).chopchop$wounds();
		if (!BodyPose.crawls(wounds, ChopChopClient.serverRules())) return;
		BodyProfile.Crawl crawl = wounds.getProfile().getCrawl();
		poseStack.translate(0, crawl.lift(), crawl.shift());
		poseStack.mulPose(new org.joml.Matrix4f().rotationX((float) Math.toRadians(crawl.pitch())));
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"))
	private void chopchop$submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		var pos = camera.pos;
		((ChopRenderState) state).chopchop$setSubmit(((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(),
				pos == null ? 0 : pos.x, pos == null ? 0 : pos.y, pos == null ? 0 : pos.z);
	}
}
