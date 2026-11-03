package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.deelter.chopchop.client.render.HeadCam;

/**
 * The death screen waits while the camera flies with the head.
 */
@Mixin(Gui.class)
public abstract class GuiMixin implements ru.deelter.chopchop.client.render.HeadCam.Screens {
	@org.spongepowered.asm.mixin.Shadow
	private Screen screen;

	/**
	 * Takes a death screen that came before the head off the screen, without the game putting it straight back.
	 */
	@Override
	public Screen chopchop$takeDeathScreen() {
		if (!(screen instanceof DeathScreen death)) return null;
		death.removed();
		screen = null;
		net.minecraft.client.Minecraft.getInstance().mouseHandler.grabMouse();
		return death;
	}

	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void chopchop$holdDeathScreen(Screen screen, CallbackInfo ci) {
		if (screen instanceof DeathScreen && HeadCam.holdDeathScreen(screen)) ci.cancel();
		// closing a screen while dead opens the death screen inside this very method: not while it is held
		else if (screen == null && HeadCam.holdingDeathScreen()) ci.cancel();
	}
}
