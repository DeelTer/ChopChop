package ru.deelter.chopchop.version;

import net.minecraft.world.entity.Entity;

/**
 * Entity calls that differ between Minecraft versions: here for 26.3, where the time is behind a setter.
 */
public final class Entities {
	private Entities() {
	}

	/**
	 * no hurt-immunity left from the last hit: the next one counts
	 */
	public static void clearInvulnerability(Entity entity) {
		entity.setInvulnerableTime(0);
	}
}
