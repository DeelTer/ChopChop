package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.ModelPick;

/**
 * After the game picked what the crosshair is on: bodies are found by their model too, see {@link ModelPick}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPickMixin {
	@Inject(method = "pick(F)V", at = @At("TAIL"), require = 0)
	private void chopchop$model(float partial, CallbackInfo ci) {
		ModelPick.afterPick(Minecraft.getInstance(), partial);
	}
}
