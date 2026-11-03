package ru.deelter.chopchop.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.*;

/**
 * Dead bodies that stay on the ground. The server says something died, which way the last blow pushed it and a
 * seed; every client then lets the same body fall the same way, then it sinks into the ground and goes.
 * <ul>
 *     <li><b>Mobs:</b> the entity itself is kept in the client world after the server removes it (see
 *     {@code ClientLevelMixin}) and drawn lying, wounds and all.</li>
 *     <li><b>Players</b> can't be kept: a player respawns under the same entity id. Their body is a copy instead, a
 *     player that is not in the world, with the same profile (so the same skin), the same armour and items, and the
 *     wounds of the player as they were when it died, a head cut off included. The real dead player is hidden while
 *     it lies dying.</li>
 * </ul>
 * The fall: the body tips over its feet away from the blow, sped up by gravity, hits the ground with a small bounce.
 * Client thread only.
 */
public final class Corpses {
	private static final int MAX = 48;
	/**
	 * ticks of sinking into the ground at the end
	 */
	public static final int SINK_TICKS = 40;
	private static final float RIGHT_ANGLE = (float) (Math.PI / 2);
	/**
	 * ids of player copies: far below anything a server hands out
	 */
	private static int nextCopyId = -1_000_000;

	private static final Map<Integer, Body> BODIES = new LinkedHashMap<>();
	/**
	 * real player id to the id of its copy, while the real one lies dying
	 */
	private static final Map<Integer, Integer> HIDDEN = new HashMap<>();
	/**
	 * set while a body is really removed, so the removal goes through
	 */
	private static boolean releasing;
	private static ClientLevel bodiesLevel;

	private Corpses() {
	}

	public static final class Body {
		final int entityId;
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
		/**
		 * the copy of a dead player, null for a mob
		 */
		final Player copy;
		/**
		 * the real player a copy is of, -1 for a mob
		 */
		final int sourceId;
		/**
		 * where the body moved by itself (slid off an edge, thrown by a blast), on top of where its entity is
		 */
		final Vec3[] offset = {Vec3.ZERO, Vec3.ZERO};
		Vec3 velocity = Vec3.ZERO;
		float height = 1.8F;
		/**
		 * ticks left of sinking away, -1 while it lies
		 */
		int sinkLeft = -1;
		boolean asleep;

		Body(Protocol.Message.Corpse message, int entityId, Player copy, int sourceId) {
			this.entityId = entityId;
			this.copy = copy;
			this.sourceId = sourceId;
			Random random = new Random(message.seed());
			// never quite the same fall: a little off the push, a little on the side
			double yaw = Math.atan2(message.dz(), message.dx()) + (random.nextDouble() - 0.5) * 0.5;
			this.dx = (float) Math.cos(yaw);
			this.dz = (float) Math.sin(yaw);
			this.roll = (float) ((random.nextDouble() - 0.5) * 0.6);
			this.ticks = Math.max(SINK_TICKS + 20, message.ticks());
			this.speed = 0.02F + message.strength() * 0.06F;
		}

		public float angle(float partial) {
			return lastAngle + (angle - lastAngle) * partial;
		}

		/**
		 * 0 until it starts to go, 1 when it is gone
		 */
		public float sink(float partial) {
			if (sinkLeft < 0) return 0;
			return 1 - Math.max(0, sinkLeft - partial) / SINK_TICKS;
		}

		/**
		 * how far the body moved by itself, interpolated
		 */
		public Vec3 offset(float partial) {
			return offset[0].lerp(offset[1], partial);
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
		if (level == null) return;
		if (bodiesLevel != level) clear();
		bodiesLevel = level;
		Entity entity = level.getEntity(message.entityId());
		if (!(entity instanceof LivingEntity living)) return;
		if (BODIES.size() >= MAX) release(BODIES.keySet().iterator().next());
		if (living instanceof Player player) {
			Player copy = copy(level, player);
			if (copy == null) return;
			int id = copy.getId();
			HIDDEN.put(player.getId(), id);
			WoundState wounds = ClientWounds.get(player.getId());
			if (wounds != null) ClientWounds.put(id, wounds);
			Body body = new Body(message, id, copy, player.getId());
			body.height = player.getBbHeight();
			BODIES.put(id, body);
		} else {
			Body body = new Body(message, message.entityId(), null, -1);
			body.height = living.getBbHeight();
			BODIES.put(message.entityId(), body);
		}
	}

	/**
	 * A player that is not in the world, standing where the dead one stands, dressed as it was.
	 */
	private static Player copy(ClientLevel level, Player player) {
		try {
			RemotePlayer copy = new RemotePlayer(level, player.getGameProfile());
			copy.setId(nextCopyId--);
			// the hat and sleeve layers, the main arm and the like
			var values = player.getEntityData().getNonDefaultValues();
			if (values != null) copy.getEntityData().assignValues(values);
			copy.setHealth(copy.getMaxHealth());
			copy.setPose(Pose.STANDING);
			copy.setPos(player.getX(), player.getY(), player.getZ());
			copy.setOldPosAndRot();
			copy.setYRot(player.getYRot());
			copy.yBodyRot = copy.yBodyRotO = player.yBodyRot;
			copy.yHeadRot = copy.yHeadRotO = player.yHeadRot;
			copy.setXRot(0);
			for (EquipmentSlot slot : EquipmentSlot.values()) copy.setItemSlot(slot, player.getItemBySlot(slot).copy());
			return copy;
		} catch (RuntimeException e) {
			ChopChop.LOG.warn("Can't make the body of {}", player.getName().getString(), e);
			return null;
		}
	}

	public static Body get(int entityId) {
		return BODIES.isEmpty() ? null : BODIES.get(entityId);
	}

	/**
	 * The real player lies dying and its copy is drawn instead.
	 */
	public static boolean hides(Entity entity) {
		if (HIDDEN.isEmpty()) return false;
		Integer copy = HIDDEN.get(entity.getId());
		return copy != null && entity instanceof LivingEntity living && living.isDeadOrDying() && BODIES.containsKey(copy);
	}

	/**
	 * Called when the server removes an entity: the body of a mob is kept. Never a player, that comes back under
	 * the same id.
	 *
	 * @return true to keep it in the world
	 */
	public static boolean holds(int entityId) {
		if (releasing) return false;
		Body body = BODIES.get(entityId);
		if (body == null || body.copy != null) return false;
		body.held = true;
		return true;
	}

	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || level != bodiesLevel) {
			// another world: what was kept belongs to the old one, there is nothing to remove here
			if (!BODIES.isEmpty() || !HIDDEN.isEmpty()) clear();
			return;
		}
		if (BODIES.isEmpty() || minecraft.isPaused()) return;
		List<Integer> done = new ArrayList<>();
		for (Body body : BODIES.values()) {
			Entity entity = body.copy != null ? body.copy : level.getEntity(body.entityId);
			if (body.copy != null) {
				follow(level, body);
			} else if (entity == null) {
				done.add(body.entityId);
				continue;
			}
			body.lastAngle = body.angle;
			body.offset[0] = body.offset[1];
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
			++body.age;
			// watched every tick the first seconds, then it sleeps and looks at the ground under it twice a second
			if (!body.asleep || body.age % 10 == 0) move(level, entity, body);
			Vec3 at = entity.position().add(body.offset[1]);
			if (body.sinkLeft >= 0) {
				if (--body.sinkLeft <= 0) done.add(body.entityId);
			} else if (body.age % 20 == 0 && ru.deelter.chopchop.client.render.Unseen.unloaded(level, at)) {
				done.add(body.entityId);
			} else if (body.age >= body.ticks && ru.deelter.chopchop.client.render.Unseen.unseen(minecraft, at.add(0, 0.3, 0))) {
				// gone while nobody looks; only someone who stares at it far too long sees it sink away
				done.add(body.entityId);
			} else if (body.age >= body.ticks * 3) {
				body.sinkLeft = SINK_TICKS;
			}
		}
		for (int id : done) release(id);
	}

	/**
	 * A body on the ground stays on the ground: when what is under it is gone it falls, and when its middle hangs
	 * over an edge (most of its weight out there) it slides off that way and falls.
	 */
	private static void move(ClientLevel level, Entity entity, Body body) {
		Vec3 feet = entity.position().add(body.offset[1]);
		float lying = (float) Math.sin(body.angle);
		// the middle of the body, where most of the weight is: half its height along the fall
		Vec3 middle = feet.add(body.dx * body.height * 0.5 * lying, 0, body.dz * body.height * 0.5 * lying);
		boolean feetHeld = solidUnder(level, feet), middleHeld = solidUnder(level, middle);
		Vec3 v = body.velocity;
		boolean moving = v.lengthSqr() > 1e-5;
		if (feetHeld && middleHeld && !moving) {
			if (body.age > 60 && body.landed) body.asleep = true;
			return;
		}
		body.asleep = false;
		if (!middleHeld && feetHeld && body.landed) {
			// tips over the edge, the way the weight hangs
			v = v.add(body.dx * 0.03, 0, body.dz * 0.03);
		}
		if (!feetHeld || v.y > 0) v = v.add(0, -0.06, 0);
		v = v.multiply(0.9, 0.98, 0.9);
		Vec3 next = body.offset[1].add(v);
		Vec3 nextFeet = entity.position().add(next);
		if (v.y < 0 && solidUnder(level, nextFeet.add(0, 0.06, 0))) {
			// lands on the top of the block under it
			BlockPosHelper top = BlockPosHelper.top(level, nextFeet.add(0, 0.06, 0));
			next = new Vec3(next.x, top.y - entity.getY(), next.z);
			v = v.y < -0.25 ? new Vec3(v.x * 0.5, -v.y * 0.25, v.z * 0.5) : new Vec3(v.x * 0.6, 0, v.z * 0.6);
		}
		if (blocked(level, nextFeet.add(0, 0.2, 0))) v = new Vec3(0, Math.min(0, v.y), 0);
		body.velocity = v.lengthSqr() < 1e-5 ? Vec3.ZERO : v;
		body.offset[1] = v.lengthSqr() < 1e-5 ? body.offset[1] : next;
	}

	/**
	 * A blast nearby throws bodies around it again, the closer the harder.
	 */
	public static void blast(Vec3 center) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || level != bodiesLevel) return;
		for (Body body : BODIES.values()) {
			Entity entity = body.copy != null ? body.copy : level.getEntity(body.entityId);
			if (entity == null) continue;
			Vec3 at = entity.position().add(body.offset[1]);
			Vec3 away = at.subtract(center);
			double distance = away.length();
			if (distance > 7) continue;
			double power = 1 - distance / 7;
			away = distance < 1e-3 ? new Vec3(0, 1, 0) : away.normalize();
			body.velocity = new Vec3(away.x * (0.2 + power * 0.6), 0.25 + power * 0.45, away.z * (0.2 + power * 0.6));
			body.asleep = false;
			// it turns over in the air and comes down again
			body.landed = false;
			body.speed = (float) (0.1 + power * 0.3);
		}
	}

	private static boolean solidUnder(ClientLevel level, Vec3 at) {
		var pos = net.minecraft.core.BlockPos.containing(at.x, at.y - 0.05, at.z);
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static boolean blocked(ClientLevel level, Vec3 at) {
		var pos = net.minecraft.core.BlockPos.containing(at.x, at.y, at.z);
		return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private record BlockPosHelper(double y) {
		static BlockPosHelper top(ClientLevel level, Vec3 at) {
			var pos = net.minecraft.core.BlockPos.containing(at.x, at.y - 0.05, at.z);
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			return new BlockPosHelper(pos.getY() + (shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y)));
		}
	}

	/**
	 * While the real player still lies dying the copy moves with it (the knockback of the last blow) and takes its
	 * newest wounds: on Paper they come a tick after the death, a head blown off by the same blast included.
	 */
	private static void follow(ClientLevel level, Body body) {
		Entity real = level.getEntity(body.sourceId);
		body.copy.setOldPosAndRot();
		if (!(real instanceof LivingEntity living) || !living.isDeadOrDying()) return;
		body.copy.setPos(real.getX(), real.getY(), real.getZ());
		WoundState wounds = ClientWounds.get(body.sourceId);
		if (wounds != null) ClientWounds.put(body.entityId, wounds);
	}

	/**
	 * Draws the copies of dead players; the bodies of mobs are drawn by the game, they are still in the world.
	 */
	public static void submit(LevelRenderContext context) {
		if (BODIES.isEmpty()) return;
		Vec3 camera = context.levelState().cameraRenderState.pos;
		if (camera == null) return;
		Minecraft minecraft = Minecraft.getInstance();
		var dispatcher = minecraft.getEntityRenderDispatcher();
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack stack = context.poseStack();
		for (Body body : BODIES.values()) {
			if (body.copy == null) continue;
			try {
				var state = dispatcher.extractEntity(body.copy, partial);
				dispatcher.submit(state, context.levelState().cameraRenderState, state.x - camera.x, state.y - camera.y, state.z - camera.z,
						stack, context.submitNodeCollector());
			} catch (RuntimeException e) {
				ChopChop.LOG.warn("Can't draw a dead player, it is left out", e);
				body.age = body.ticks;
			}
		}
	}

	/**
	 * Lets a body go: out of the client world if the server is done with it.
	 */
	private static void release(int entityId) {
		Body body = BODIES.remove(entityId);
		if (body == null) return;
		if (body.copy != null) {
			HIDDEN.remove(body.sourceId);
			ClientWounds.clear(entityId);
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (!body.held || level == null || level != bodiesLevel) return;
		releasing = true;
		try {
			level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
		} finally {
			releasing = false;
		}
	}

	public static void clear() {
		for (Body body : BODIES.values()) if (body.copy != null) ClientWounds.clear(body.entityId);
		BODIES.clear();
		HIDDEN.clear();
		bodiesLevel = null;
	}

	public static int size() {
		return BODIES.size();
	}
}
