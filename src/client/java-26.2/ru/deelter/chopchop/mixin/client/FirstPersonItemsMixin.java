package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.client.render.FirstPerson;

/**
 * First person: a hand that is cut off is not drawn at all, nor what it holds. The stump is out of view there.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class FirstPersonItemsMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void chopchop$hide(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand, float attack, ItemStack itemStack,
							   float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		// looking out of a flying head: no hands
		if (ru.deelter.chopchop.client.render.HeadCam.active() || ru.deelter.chopchop.client.render.HeadCam.expecting()
				|| ru.deelter.chopchop.client.render.KillCam.active()) {
			ci.cancel();
			return;
		}
		if (!ClientConfig.get().isHideItems() || !ClientConfig.get().isFirstPerson()) return;
		if (FirstPerson.handGone(FirstPerson.ownWounds(), FirstPerson.side(player, hand))) ci.cancel();
	}
}
