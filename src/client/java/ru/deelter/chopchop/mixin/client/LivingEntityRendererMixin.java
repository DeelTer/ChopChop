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
import ru.deelter.chopchop.client.Corpses;
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
		Corpses.Body body = Corpses.get(entity.getId());
		if (body == null) {
			((ChopRenderState) state).chopchop$setCorpse(null);
			return;
		}
		// a body falls our way, not the vanilla way: no turn onto its side, no red, no walking legs
		state.deathTime = 0;
		state.hasRedOverlay = false;
		state.walkAnimationSpeed = 0;
		// the body of a dead player wears no name
		state.nameTag = null;
		// where it slid or was thrown to by itself
		var moved = body.offset(partialTicks);
		state.x += moved.x;
		state.y += moved.y;
		state.z += moved.z;
		// the fall in the frame the model is turned into: rotated by 180 - body yaw about y
		double turn = Math.toRadians(180.0 - state.bodyRot);
		float cos = (float) Math.cos(turn), sin = (float) Math.sin(turn);
		float lx = body.dx() * cos - body.dz() * sin;
		float lz = body.dx() * sin + body.dz() * cos;
		float[] corpse = new float[6 + Corpses.JOINTS];
		corpse[0] = lx;
		corpse[1] = lz;
		corpse[2] = body.angle(partialTicks);
		corpse[3] = body.roll();
		corpse[4] = body.sink(partialTicks);
		corpse[5] = body.width();
		// the limp joints, for the model (Corpse.pose)
		for (int i = 0; i < Corpses.JOINTS; i++) corpse[6 + i] = body.joint(i, partialTicks);
		((ChopRenderState) state).chopchop$setCorpse(corpse);
	}

	/**
	 * A mob without legs lies down and crawls, turned exactly as the server finds hits on it ({@link BodyPose}).
	 * Players crawl in the vanilla pose instead, which the game draws itself.
	 */
	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
			at = @At("TAIL"))
	private void chopchop$crawl(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		float[] corpse = ((ChopRenderState) state).chopchop$corpse();
		if (corpse != null) {
			chopchop$lie(poseStack, corpse);
			return;
		}
		if (state instanceof AvatarRenderState || !ChopChopClient.serverSpeaks()) return;
		WoundState wounds = ((ChopRenderState) state).chopchop$wounds();
		if (!BodyPose.crawls(wounds, ChopChopClient.serverRules())) return;
		BodyProfile.Crawl crawl = wounds.getProfile().getCrawl();
		poseStack.translate(0, crawl.lift(), crawl.shift());
		poseStack.mulPose(new org.joml.Matrix4f().rotationX((float) Math.toRadians(crawl.pitch())));
	}

	/**
	 * Tips the body over its feet towards the fall, lifted by half its thickness so it lies on the ground and does not
	 * cut into it, and at its end sinks into the ground.
	 */
	@org.spongepowered.asm.mixin.Unique
	private static void chopchop$lie(PoseStack poseStack, float[] corpse) {
		float lx = corpse[0], lz = corpse[1], angle = corpse[2], roll = corpse[3], sink = corpse[4], width = corpse[5];
		float lying = (float) Math.sin(angle);
		// half the thickness of the thickest part (a head is half the width of the body standing)
		poseStack.translate(0, lying * width * 0.42F - sink * 0.45F, 0);
		// about up x fall: the top goes the way of the fall
		org.joml.Vector3f axis = new org.joml.Vector3f(lz, 0, -lx);
		if (axis.lengthSquared() < 1e-6F) axis.set(1, 0, 0);
		poseStack.rotateAround(new org.joml.Quaternionf().rotationAxis(angle, axis.normalize()), 0, 0, 0);
		// lying, it rolls a little onto a side
		poseStack.rotateAround(new org.joml.Quaternionf().rotationY(roll * lying), 0, 0, 0);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At("HEAD"))
	private void chopchop$submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		var pos = camera.pos;
		((ChopRenderState) state).chopchop$setSubmit(((LivingEntityRenderer<?, ?, ?>) (Object) this).getModel(),
				pos == null ? 0 : pos.x, pos == null ? 0 : pos.y, pos == null ? 0 : pos.z);
		try {
			((ChopRenderState) state).chopchop$setTexture(chopchop$texture(state));
		} catch (RuntimeException e) {
			((ChopRenderState) state).chopchop$setTexture(null);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	@org.spongepowered.asm.mixin.Unique
	private net.minecraft.resources.Identifier chopchop$texture(LivingEntityRenderState state) {
		return ((LivingEntityRenderer) (Object) this).getTextureLocation(state);
	}
}
