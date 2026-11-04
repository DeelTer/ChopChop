package ru.deelter.chopchop.version;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Entity calls that differ between Minecraft versions: here for 26.3, where the time is behind a setter, and a
 * living entity's cooldown between hits is a field of its own.
 */
public final class Entities {
	private Entities() {
	}

	/**
	 * no hurt-immunity left from the last hit: the next one counts. In 26.3 a living entity takes no new damage
	 * while {@code damageCooldownTime} runs, apart from {@code invulnerableTime}
	 */
	public static void clearInvulnerability(Entity entity) {
		entity.setInvulnerableTime(0);
		if (entity instanceof LivingEntity living) living.damageCooldownTime = 0;
	}
}
