package ru.deelter.chopchop.mixin;

import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.Crawling;

/**
 * A player without legs stays in the crawling pose.
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void chopchop$crawl(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (self.isSpectator() || self.isPassenger() || self.isSleeping() || self.isFallFlying()) return;
		if (Crawling.crawls(self)) {
			self.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
