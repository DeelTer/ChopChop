package ru.deelter.chopchop.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.server.ChopServer;

/**
 * A mob without legs lies where it fell: no goals, no path, no attacks, only its head turns after the nearest
 * player. The AI is skipped here, not switched off with NoAI, so nothing of it is saved with the mob.
 */
@Mixin(Mob.class)
public abstract class MobAiMixin {
	private static final double LOOK_RANGE = 16;
	/**
	 * how far the head turns from the body, degrees
	 */
	private static final float NECK = 75;

	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void chopchop$lie(CallbackInfo ci) {
		Mob self = (Mob) (Object) this;
		if (!ChopServer.lies(self)) return;
		ci.cancel();
		self.getNavigation().stop();
		self.setTarget(null);
		self.setZza(0);
		self.setXxa(0);
		self.setYya(0);
		self.setJumping(false);
		Player player = self.level().getNearestPlayer(self, LOOK_RANGE);
		if (player == null) return;
		Vec3 to = player.getEyePosition().subtract(self.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
		float neck = Mth.clamp(Mth.wrapDegrees(yaw - self.yBodyRot), -NECK, NECK);
		self.setYHeadRot(Mth.approachDegrees(self.getYHeadRot(), self.yBodyRot + neck, 10));
		self.setXRot(Mth.approach(self.getXRot(), Mth.clamp(pitch, -40, 40), 5));
	}
}
