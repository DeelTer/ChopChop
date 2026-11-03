package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.render.HeadCam;
import ru.deelter.chopchop.client.render.KillCam;

/**
 * Puts the camera into a flying head ({@link HeadCam}) or around one ({@link KillCam}). Same place as the camera of VRCamera: right after the
 * game aligned it with the entity.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private boolean detached;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract Vec3 position();

	@Shadow
	public abstract float yRot();

	@Shadow
	public abstract float xRot();

	@Shadow
	public abstract float getCameraEntityPartialTicks(DeltaTracker deltaTracker);

	@Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V", shift = At.Shift.AFTER))
	private void chopchop$headCam(DeltaTracker deltaTracker, CallbackInfo ci) {
		HeadCam.View view = HeadCam.view(getCameraEntityPartialTicks(deltaTracker));
		if (view != null) {
			setRotation(view.yaw(), view.pitch());
			setPosition(view.position());
			detached = true;
		} else if ((view = KillCam.view(getCameraEntityPartialTicks(deltaTracker), position(), yRot(), xRot())) != null) {
			setRotation(view.yaw(), view.pitch());
			setPosition(view.position());
			detached = true;
		} else if (HeadCam.expecting()) {
			// the body is drawn this frame, so the head can be taken from it
			detached = true;
		}
	}
}
