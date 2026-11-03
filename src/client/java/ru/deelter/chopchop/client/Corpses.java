package ru.deelter.chopchop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import ru.deelter.chopchop.core.net.Protocol;

import java.util.*;

/**
 * Dead mobs that stay on the ground as bodies. The server says a mob died, which way the last blow pushed it and a
 * seed; every client then lets the same body fall the same way. The entity itself is kept in the client world after
 * the server removes it (see {@code ClientLevelMixin}) and drawn lying, wounds and all, until its time is up; then it
 * sinks into the ground and goes.
 * <p>
 * The fall: the body tips over its feet away from the blow, sped up by gravity, hits the ground with a small bounce.
 * Where it slides is the vanilla knockback of the killing blow, which the server still sends for the moment the
 * entity lives on there. Client thread only.
 */
public final class Corpses {
	private static final int MAX = 48;
	/**
	 * ticks of sinking into the ground at the end
	 */
	public static final int SINK_TICKS = 40;
	private static final float RIGHT_ANGLE = (float) (Math.PI / 2);

	private static final Map<Integer, Body> BODIES = new LinkedHashMap<>();
	/**
	 * set while a body is really removed, so the removal goes through
	 */
	private static boolean releasing;

	private Corpses() {
	}

	public static final class Body {
		final int entityId;
		/**
		 * direction of the fall in the world, unit
		 */
		final float dx, dz;
		final float roll;
		final int ticks;
		int age;
		float angle, lastAngle, speed;
		boolean landed;
		/**
		 * the server removed the entity, only this client keeps it
		 */
		boolean held;

		Body(Protocol.Message.Corpse message) {
			Random random = new Random(message.seed());
			// never quite the same fall: a little off the push, a little on the side
			double yaw = Math.atan2(message.dz(), message.dx()) + (random.nextDouble() - 0.5) * 0.5;
			this.dx = (float) Math.cos(yaw);
			this.dz = (float) Math.sin(yaw);
			this.roll = (float) ((random.nextDouble() - 0.5) * 0.6);
			this.ticks = Math.max(SINK_TICKS + 20, message.ticks());
			this.speed = 0.02F + message.strength() * 0.06F;
		}

		/**
		 * how far it has tipped, 0 standing to a right angle lying
		 */
		public float angle(float partial) {
			return lastAngle + (angle - lastAngle) * partial;
		}

		/**
		 * 0 until it starts to go, 1 when it is gone
		 */
		public float sink(float partial) {
			float left = ticks - age - partial;
			return left >= SINK_TICKS ? 0 : 1 - Math.max(0, left) / SINK_TICKS;
		}

		public float dx() {
			return dx;
		}

		public float dz() {
			return dz;
		}

		public float roll() {
			return roll;
		}
	}

	public static void add(Protocol.Message.Corpse message) {
		if (!ru.deelter.chopchop.client.config.ClientConfig.get().isCorpses()) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !(level.getEntity(message.entityId()) instanceof LivingEntity)) return;
		if (BODIES.size() >= MAX) release(BODIES.keySet().iterator().next());
		BODIES.put(message.entityId(), new Body(message));
	}

	public static Body get(int entityId) {
		return BODIES.isEmpty() ? null : BODIES.get(entityId);
	}

	/**
	 * Called when the server removes an entity: a body is kept.
	 *
	 * @return true to keep it in the world
	 */
	public static boolean holds(int entityId) {
		if (releasing) return false;
		Body body = BODIES.get(entityId);
		if (body == null) return false;
		body.held = true;
		return true;
	}

	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			BODIES.clear();
			return;
		}
		if (BODIES.isEmpty() || minecraft.isPaused()) return;
		List<Integer> done = new ArrayList<>();
		for (Body body : BODIES.values()) {
			Entity entity = level.getEntity(body.entityId);
			if (entity == null) {
				done.add(body.entityId);
				continue;
			}
			body.lastAngle = body.angle;
			if (!body.landed) {
				// a stick falling over its foot: the further it leans, the faster it goes
				body.speed += 0.035F * (float) Math.sin(body.angle + 0.2F);
				body.angle += body.speed;
				if (body.angle >= RIGHT_ANGLE) {
					body.angle = RIGHT_ANGLE;
					body.speed = -body.speed * 0.22F;
					if (Math.abs(body.speed) < 0.03F) body.landed = true;
				}
			}
			if (++body.age >= body.ticks) done.add(body.entityId);
		}
		for (int id : done) release(id);
	}

	/**
	 * Lets a body go: out of the client world if the server is done with it.
	 */
	private static void release(int entityId) {
		Body body = BODIES.remove(entityId);
		ClientLevel level = Minecraft.getInstance().level;
		if (body == null || !body.held || level == null) return;
		releasing = true;
		try {
			level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
		} finally {
			releasing = false;
		}
	}

	public static void clear() {
		BODIES.clear();
	}

	public static int size() {
		return BODIES.size();
	}
}
