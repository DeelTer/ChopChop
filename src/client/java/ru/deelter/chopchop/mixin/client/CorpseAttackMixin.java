package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.ModelPick;

/**
 * A click at a dead body cuts it (see {@link ModelPick#cutCorpse}) instead of the vanilla attack, which would only
 * swing at the air.
 */
@Mixin(Minecraft.class)
public abstract class CorpseAttackMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true, require = 0)
	private void chopchop$cut(CallbackInfoReturnable<Boolean> cir) {
		if (ModelPick.cutCorpse((Minecraft) (Object) this)) cir.setReturnValue(false);
	}
}
