package ru.deelter.chopchop.version;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

/**
 * A swing of the arm, which Minecraft changed between versions: here for 26.3, where a swing names its animation.
 */
public final class Swings {
	private Swings() {
	}

	/**
	 * the others see the player swing the main hand; his own client swung already
	 */
	public static void swingForOthers(ServerPlayer player) {
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
	}
}
