package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.client.render.*;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Entity models are posed right before they are drawn, from a deferred submit. Between the pose and the draw is
 * where the wounds of the entity are put in place for {@link GoreRender}; after the draw the meat and bones it
 * collected go into their own buffers and everything is put back.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {
	@Inject(method = "prepareModel", at = @At("HEAD"))
	private void chopchop$clean(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		// a draw that threw before its end must not leak into the next one
		GoreRender.reset();
	}

	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER))
	private void chopchop$posed(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		try {
			Model<?> model = submit.model();
			RenderType type = submit.renderType();
			boolean gore = !type.isOutline() && (submit.tintedColor() >>> 24) == 0xFF;
			if (submit.state() instanceof ChopRenderState state) {
				if (model == state.chopchop$mainModel()) {
					// the pose as drawn, for the crosshair (ModelPick): a swinging arm is hit where it is
					AnimatedPose.capture(model, state.chopchop$entityId(),
							((net.minecraft.client.renderer.entity.state.EntityRenderState) submit.state()).distanceToCameraSq, submit.pose(),
							state.chopchop$camera());
				}
				WoundState wounds = state.chopchop$wounds();
				if (wounds == null) return;
				double max = ClientConfig.get().getMaxDistance();
				boolean far = ((net.minecraft.client.renderer.entity.state.EntityRenderState) submit.state()).distanceToCameraSq > max * max;
				Bindings.ensure(model);
				if (model == state.chopchop$mainModel()) {
					var entityState = (net.minecraft.client.renderer.entity.state.EntityRenderState) submit.state();
					Debris.capture(model, wounds, state.chopchop$entityId(), submit.pose(), type, state.chopchop$texture(), state.chopchop$camera(),
							new net.minecraft.world.phys.Vec3(entityState.x, entityState.y, entityState.z));
					GoreRender.begin(wounds, GoreRender.Role.MAIN, gore, far);
				} else {
					GoreRender.begin(wounds, type.toString().contains("armor") ? GoreRender.Role.ARMOR : GoreRender.Role.LAYER, false, far);
				}
			} else if (model instanceof Model.Simple) {
				WoundState wounds = FirstPerson.forArm(model.root());
				if (wounds != null) GoreRender.begin(wounds, GoreRender.Role.MAIN, gore);
			}
		} catch (RuntimeException e) {
			ChopChop.LOG.warn("Can't prepare wounds for {}", submit.model().getClass().getName(), e);
			GoreRender.reset();
		}
	}

	@Inject(method = "prepareModel", at = @At("TAIL"))
	private void chopchop$drawn(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		GoreRender.end(((RenderTypeFeatureRendererInvoker) this)::chopchop$getVertexBuilder);
	}
}
