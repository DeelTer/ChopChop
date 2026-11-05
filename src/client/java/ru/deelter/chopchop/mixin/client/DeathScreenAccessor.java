package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * A death screen held back while the head flies was looked at all that time: its buttons need not wait again.
 */
@Mixin(DeathScreen.class)
public interface DeathScreenAccessor {
	@Accessor("delayTicker")
	void chopchop$setDelayTicker(int ticks);

	@Invoker("setButtonsActive")
	void chopchop$setButtonsActive(boolean active);
}
