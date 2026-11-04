package ru.deelter.chopchop.client.version;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * The own swing of the arm, which Minecraft changed between versions: here for 26.3, where a swing names its animation.
 */
public final class ClientSwings {
	private ClientSwings() {
	}

	public static void swing(LocalPlayer player) {
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
	}
}
