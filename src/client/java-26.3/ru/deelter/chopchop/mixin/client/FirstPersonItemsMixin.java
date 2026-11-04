package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
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
 * 26.3 draws first person hands from a render state, in a renderer of their own.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonItemsMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void chopchop$hide(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState handsState, float frameInterp, float xRot,
							   InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
							   SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		// looking out of a flying head: no hands
		if (ru.deelter.chopchop.client.render.HeadCam.active() || ru.deelter.chopchop.client.render.HeadCam.expecting()
				|| ru.deelter.chopchop.client.render.DeathCam.active()
				|| ru.deelter.chopchop.client.render.KillCam.active()) {
			ci.cancel();
			return;
		}
		if (!ClientConfig.get().isHideItems() || !ClientConfig.get().isFirstPerson()) return;
		var player = Minecraft.getInstance().player;
		if (player != null && FirstPerson.handGone(FirstPerson.ownWounds(), FirstPerson.side(player, hand))) ci.cancel();
	}
}
