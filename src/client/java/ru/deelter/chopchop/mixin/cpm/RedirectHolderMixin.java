package ru.deelter.chopchop.mixin.cpm;

import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.cpm.CpmWounds;

/**
 * CPM resets and animates every element of a model in bindFirstSetup, right before its parts are drawn: what is cut
 * off is hidden at its tail, for that one draw. The same place ModelInteractions bends CPM parts.
 */
@Mixin(value = ModelRenderManager.RedirectHolder.class, remap = false)
public abstract class RedirectHolderMixin {
	@Shadow
	public ModelDefinition def;
	@Shadow
	public Player<?> playerObj;

	@Inject(method = "bindFirstSetup", at = @At("TAIL"))
	private void chopchop$cut(boolean isPoseSetup, CallbackInfo ci) {
		if (playerObj != null && def != null) CpmWounds.apply(def, playerObj.getUUID());
	}
}
