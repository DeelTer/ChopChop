package ru.deelter.chopchop.client.render;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Legs kicking at a block: a player with no arms left digs with a leg. The leg swings forward and back once per
 * kick, on top of whatever the leg does otherwise. Render thread and client thread.
 */
public final class Kicks {
	private static final long KICK_NANOS = 280_000_000L;
	/**
	 * how far the leg swings up and forward, radians
	 */
	private static final float LIFT = 1.15F;
	private static final Map<Integer, long[]> KICKS = new HashMap<>();

	private Kicks() {
	}

	/**
	 * @param leg 0 right, 1 left
	 */
	public static synchronized void start(int entityId, int leg) {
		KICKS.put(entityId, new long[]{System.nanoTime(), leg});
	}

	public static synchronized boolean kicking(int entityId) {
		return KICKS.containsKey(entityId);
	}

	public static synchronized void clear() {
		KICKS.clear();
	}

	/**
	 * Called right after a model of the entity was posed for its draw.
	 */
	public static synchronized void pose(Model<?> model, int entityId) {
		if (KICKS.isEmpty()) return;
		long[] kick = KICKS.get(entityId);
		if (kick == null) return;
		long age = System.nanoTime() - kick[0];
		if (age > KICK_NANOS) {
			KICKS.remove(entityId);
			return;
		}
		List<ModelPart> path = Bindings.path(model, kick[1] == 0 ? "right_leg" : "left_leg");
		if (path == null || path.isEmpty()) return;
		float t = (float) age / KICK_NANOS;
		path.getLast().xRot -= (float) Math.sin(t * Math.PI) * LIFT;
	}

	public static synchronized void tick() {
		long now = System.nanoTime();
		for (Iterator<long[]> it = KICKS.values().iterator(); it.hasNext(); ) {
			if (now - it.next()[0] > KICK_NANOS) it.remove();
		}
	}
}
