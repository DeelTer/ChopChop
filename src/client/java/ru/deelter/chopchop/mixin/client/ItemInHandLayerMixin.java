package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.client.render.ChopRenderState;
import ru.deelter.chopchop.client.render.FirstPerson;

/**
 * Hiding an arm does not hide what it holds: an item in a hand that is cut off is not drawn. Only the look of it,
 * the item stays in the hand as far as the game is concerned.
 */
@Mixin(ItemInHandLayer.class)
public abstract class ItemInHandLayerMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void chopchop$hide(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
							   PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, CallbackInfo ci) {
		if (ClientConfig.get().isHideItems() && FirstPerson.handGone(((ChopRenderState) state).chopchop$wounds(), arm)) ci.cancel();
	}
}
