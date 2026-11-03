package ru.deelter.chopchop.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.render.Bindings;
import ru.deelter.chopchop.client.render.FirstPerson;

/**
 * The first person arm is drawn alone: note it, so its draw finds the player's wounds.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "renderHand", at = @At("HEAD"))
	private void chopchop$hand(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, Identifier skinTexture, ModelPart arm,
							   boolean hasSleeve, CallbackInfo ci) {
		Bindings.ensure(((AvatarRenderer<?>) (Object) this).getModel());
		FirstPerson.noteArm(arm);
	}
}
