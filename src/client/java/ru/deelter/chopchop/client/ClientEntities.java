package ru.deelter.chopchop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * An entity by the id the server talks about: one in the world, or the body of a dead player, which is in none.
 */
public final class ClientEntities {
	private ClientEntities() {
	}

	public static Entity find(int id) {
		var level = Minecraft.getInstance().level;
		Entity entity = level == null ? null : level.getEntity(id);
		return entity != null ? entity : Corpses.entity(id);
	}
}
