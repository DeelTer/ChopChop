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
	public static final int HEAD_YAW = 0, HEAD_PITCH = 1, RIGHT_ARM_X = 2, RIGHT_ARM_Z = 3, LEFT_ARM_X = 4, LEFT_ARM_Z = 5,
			RIGHT_LEG_X = 6, RIGHT_LEG_Z = 7, LEFT_LEG_X = 8, LEFT_LEG_Z = 9, JOINTS = 10;
	/**
	 * how stiff the joints are and how fast they calm down
	 */
	private static final float JOINT_SPRING = 0.18F, JOINT_DAMPING = 0.22F, JOINT_LIMIT = 1.6F;
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
		float dx, dz;
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
		float width = 0.6F;
		/**
		 * ticks left of sinking away, -1 while it lies
		 */
		int sinkLeft = -1;
		boolean asleep;
		/**
		 * The joints of a limp body, each an angle and how fast it turns: springs that go slack towards a pose of their
		 * own (arms flung out, a leg bent, the head turned away), shaken by every knock. See {@link #JOINTS}
		 */
		final float[] joint = new float[JOINTS], lastJoint = new float[JOINTS], jointSpeed = new float[JOINTS], slack = new float[JOINTS];
		final Random jolts;

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
			// where each joint goes slack: the same on every client, from the seed
			slack[HEAD_YAW] = (float) ((random.nextDouble() - 0.5) * 1.6);
			slack[HEAD_PITCH] = (float) ((random.nextDouble() - 0.3) * 0.6);
			slack[RIGHT_ARM_X] = (float) ((random.nextDouble() - 0.5) * 1.2);
			slack[RIGHT_ARM_Z] = (float) (0.15 + random.nextDouble() * 0.7);
			slack[LEFT_ARM_X] = (float) ((random.nextDouble() - 0.5) * 1.2);
			slack[LEFT_ARM_Z] = (float) -(0.15 + random.nextDouble() * 0.7);
			slack[RIGHT_LEG_X] = (float) ((random.nextDouble() - 0.5) * 0.7);
			slack[RIGHT_LEG_Z] = (float) (0.03 + random.nextDouble() * 0.3);
			slack[LEFT_LEG_X] = (float) ((random.nextDouble() - 0.5) * 0.7);
			slack[LEFT_LEG_Z] = (float) -(0.03 + random.nextDouble() * 0.3);
			this.jolts = new Random(message.seed() * 31L + 7);
		}

		/**
		 * The joints now, interpolated: radians, see {@link #JOINTS}.
		 */
		public float joint(int index, float partial) {
			return lastJoint[index] + (joint[index] - lastJoint[index]) * partial;
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

		/**
		 * width of the body standing, blocks: half its thickness lifts it off the ground as it lies
		 */
		public float width() {
			return width;
		}

		public int id() {
			return entityId;
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
			// the id the server gave the body, for blows at it; an old server gives none
			int id = message.corpseId() != message.entityId() ? message.corpseId() : nextCopyId--;
			Player copy = copy(level, player, id);
			if (copy == null) return;
			HIDDEN.put(player.getId(), id);
			WoundState wounds = ClientWounds.get(player.getId());
			if (wounds != null) {
				ClientWounds.put(id, wounds);
				// seen: kept even while nobody has drawn the copy yet, it has no entity in the world to wait for
				ClientWounds.get(id);
			}
			Body body = new Body(message, id, copy, player.getId());
			size(body, player);
			pickWay(level, copy, body);
			BODIES.put(id, body);
			// the own body: the camera leaves the eyes to look at it
			if (player == Minecraft.getInstance().player) ru.deelter.chopchop.client.render.DeathCam.follow(id, message.dx(), message.dz());
		} else {
			Body body = new Body(message, message.entityId(), null, -1);
			size(body, living);
			pickWay(level, living, body);
			BODIES.put(message.entityId(), body);
		}
	}

	/**
	 * The size of the body standing: a dead entity has the tiny box of the dying pose.
	 */
	private static void size(Body body, LivingEntity entity) {
		var standing = entity.getDimensions(Pose.STANDING);
		body.height = Math.max(0.3F, standing.height());
		body.width = Math.max(0.2F, standing.width());
	}

	/**
	 * A player that is not in the world, standing where the dead one stands, dressed as it was.
	 */
	private static Player copy(ClientLevel level, Player player, int id) {
		try {
			RemotePlayer copy = new RemotePlayer(level, player.getGameProfile());
			copy.setId(id);
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

	/**
	 * The entity of a body: the mob kept in the world, or the copy of a player that is in no world.
	 */
	public static Entity entity(int entityId) {
		Body body = get(entityId);
		if (body == null) return null;
		if (body.copy != null) return body.copy;
		ClientLevel level = Minecraft.getInstance().level;
		return level == null ? null : level.getEntity(entityId);
	}

	/**
	 * About the middle of a body as it lies, for the blood of a blow at it.
	 */
	public static Vec3 middle(int entityId) {
		Body body = get(entityId);
		Entity entity = entity(entityId);
		if (body == null || entity == null) return null;
		double along = body.height * 0.45 * Math.sin(body.angle);
		return entity.position().add(body.offset[1]).add(body.dx * along, 0.2 + body.height * 0.45 * Math.cos(body.angle), body.dz * along);
	}

	/**
	 * A box around a body as it lies (or still stands, early in its fall), for something solid to meet it: from its
	 * feet along the way it fell, as wide as it is.
	 */
	public static net.minecraft.world.phys.AABB box(int entityId) {
		Body body = get(entityId);
		Entity entity = entity(entityId);
		if (body == null || entity == null) return null;
		Vec3 feet = entity.position().add(body.offset[1]);
		double along = body.height * Math.sin(body.angle);
		Vec3 head = feet.add(body.dx * along, body.height * Math.cos(body.angle), body.dz * along);
		return new net.minecraft.world.phys.AABB(feet, head).inflate(body.width / 2, 0.15, body.width / 2);
	}

	/**
	 * Every body there is, for the crosshair.
	 */
	public static Collection<Body> all() {
		return BODIES.values();
	}

	public static Body get(int entityId) {
		return BODIES.isEmpty() ? null : BODIES.get(entityId);
	}

	/**
	 * The player a body is the copy of, -1 for a mob or no body.
	 */
	public static int sourceOf(int entityId) {
		Body body = get(entityId);
		return body == null ? -1 : body.sourceId;
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
				float next = Math.min(RIGHT_ANGLE, body.angle + body.speed);
				if (body.speed > 0 && !clear(level, entity.position().add(body.offset[1]), body, next)) {
					// a wall or a step in the way: it leans against it and stays so
					body.speed = 0;
					body.landed = true;
				} else {
					body.angle = Math.max(0, next);
				}
				if (body.angle >= RIGHT_ANGLE) {
					body.angle = RIGHT_ANGLE;
					// it hits the ground: arms and legs and head fly about
					jolt(body, Math.abs(body.speed) * 4);
					body.speed = -body.speed * 0.22F;
					if (Math.abs(body.speed) < 0.03F) body.landed = true;
				}
			}
			joints(body);
			++body.age;
			// watched every tick the first seconds, then it sleeps and looks at the ground under it twice a second
			if (!body.asleep || body.age % 10 == 0) {
				move(level, entity, body);
				if (body.landed) unstick(level, entity, body);
			}
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
		double ground = ground(level, feet, body);
		boolean held = !Double.isNaN(ground) && feet.y <= ground + 0.03;
		boolean middleHeld = !Double.isNaN(columnTop(level, middle, feet.y));
		Vec3 v = body.velocity;
		boolean moving = v.lengthSqr() > 1e-5;
		if (held && middleHeld && !moving && feet.y >= ground - 1e-3) {
			if (body.age > 60 && body.landed) body.asleep = true;
			return;
		}
		body.asleep = false;
		if (!middleHeld && held && body.landed) {
			// tips over the edge, the way the weight hangs
			v = v.add(body.dx * 0.03, 0, body.dz * 0.03);
		}
		if (!held || v.y > 0) v = v.add(0, -0.06, 0);
		v = v.multiply(0.9, 0.98, 0.9);
		Vec3 next = body.offset[1].add(v);
		Vec3 nextFeet = entity.position().add(next);
		// never into a block: not the feet, not anything along the body as it lies
		if (!clear(level, nextFeet, body, body.angle)) {
			v = new Vec3(0, Math.min(0, v.y), 0);
			next = new Vec3(body.offset[1].x, next.y, body.offset[1].z);
			nextFeet = entity.position().add(next);
			if (!clear(level, nextFeet, body, body.angle)) {
				next = body.offset[1];
				nextFeet = feet;
				v = Vec3.ZERO;
			}
		}
		// lands on (or is lifted onto) the highest ground under it: a carpet, a slab, a step count as ground
		double under = ground(level, nextFeet, body);
		if (!Double.isNaN(under) && nextFeet.y <= under) {
			if (v.y < -0.1) jolt(body, (float) -v.y * 2);
			next = new Vec3(next.x, under - entity.getY(), next.z);
			v = v.y < -0.25 ? new Vec3(v.x * 0.5, -v.y * 0.25, v.z * 0.5) : new Vec3(v.x * 0.6, 0, v.z * 0.6);
		}
		body.velocity = v.lengthSqr() < 1e-5 ? Vec3.ZERO : v;
		body.offset[1] = next;
	}

	/**
	 * blocks up to this high above the feet are ground to lie on, not something in the way
	 */
	private static final double LOW = 0.5;

	/**
	 * The highest ground under the body as it lies (under its feet and along its length), world y; NaN with none
	 * within reach below.
	 */
	private static double ground(ClientLevel level, Vec3 feet, Body body) {
		double best = columnTop(level, feet, feet.y);
		double sin = Math.sin(body.angle);
		for (double t = 0.3; t <= 1.0001; t += 0.35) {
			double along = body.height * t * sin;
			double top = columnTop(level, feet.add(body.dx * along, 0, body.dz * along), feet.y);
			if (!Double.isNaN(top) && (Double.isNaN(best) || top > best)) best = top;
		}
		return best;
	}

	/**
	 * The top of what is under a point, from {@link #LOW} above the feet down to a block below; NaN if nothing.
	 */
	private static double columnTop(ClientLevel level, Vec3 at, double feetY) {
		int x = net.minecraft.util.Mth.floor(at.x), z = net.minecraft.util.Mth.floor(at.z);
		for (int y = net.minecraft.util.Mth.floor(feetY + LOW); y >= net.minecraft.util.Mth.floor(feetY) - 1; y--) {
			var pos = new net.minecraft.core.BlockPos(x, y, z);
			var shape = level.getBlockState(pos).getCollisionShape(level, pos);
			if (shape.isEmpty()) continue;
			double top = y + shape.max(net.minecraft.core.Direction.Axis.Y);
			if (top <= feetY + LOW + 1e-3) return top;
			return Double.NaN;
		}
		return Double.NaN;
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
			jolt(body, (float) (0.5 + power));
		}
	}

	/**
	 * The joints go slack towards their pose as the body comes to lie, and swing after a knock.
	 */
	private static void joints(Body body) {
		float lying = (float) Math.sin(body.angle);
		for (int i = 0; i < JOINTS; i++) {
			body.lastJoint[i] = body.joint[i];
			float target = body.slack[i] * lying;
			body.jointSpeed[i] += (target - body.joint[i]) * JOINT_SPRING - body.jointSpeed[i] * JOINT_DAMPING;
			body.joint[i] = Math.clamp(body.joint[i] + body.jointSpeed[i], -JOINT_LIMIT, JOINT_LIMIT);
		}
	}

	/**
	 * A blow landed on a body (the server says so, every client alike): it twitches, the harder the more it cut, and
	 * gives a little hop.
	 */
	public static void struck(int entityId, byte kind) {
		Body body = get(entityId);
		if (body == null) return;
		float power = switch (kind) {
			case Protocol.EVENT_SEVER, Protocol.EVENT_BLAST -> 0.55F;
			case Protocol.EVENT_WOUND -> 0.35F;
			default -> 0.2F;
		};
		jolt(body, power);
		body.velocity = body.velocity.add(0, 0.04 + power * 0.08, 0);
		body.asleep = false;
	}

	/**
	 * A knock: every joint gets a push of its own, up to {@code power} radians a tick.
	 */
	private static void jolt(Body body, float power) {
		float p = Math.min(0.6F, power);
		if (p < 0.02F) return;
		for (int i = 0; i < JOINTS; i++) body.jointSpeed[i] += (body.jolts.nextFloat() - 0.5F) * 2 * p;
		body.asleep = false;
	}

	/**
	 * Whether the body, tipped this far over its feet, is clear of blocks: points along its length from the hips to
	 * the top of the head, down its middle and at both its sides, a little above the ground it lies on.
	 */
	private static boolean clear(ClientLevel level, Vec3 feet, Body body, float angle) {
		double sin = Math.sin(angle), cos = Math.cos(angle);
		// across the body, level: its shoulders and hips stick out to the sides of the line of the fall
		double side = body.width * 0.4;
		double sx = -body.dz * side, sz = body.dx * side;
		for (double t = 0.3; t <= 1.0001; t += 0.175) {
			double along = body.height * t;
			Vec3 point = feet.add(body.dx * along * sin, along * cos + 0.12, body.dz * along * sin);
			if (blockedAbove(level, point, feet.y)) return false;
			if (t < 0.95 && (blockedAbove(level, point.add(sx, 0, sz), feet.y) || blockedAbove(level, point.add(-sx, 0, -sz), feet.y))) return false;
		}
		return true;
	}

	/**
	 * A body found inside blocks (a block placed into it, a fall into a gap): pushed out to the nearest place within
	 * half a block where it fits, or raised until it stands clear enough to lean.
	 */
	private static void unstick(ClientLevel level, Entity entity, Body body) {
		Vec3 feet = entity.position().add(body.offset[1]);
		if (clear(level, feet, body, body.angle)) return;
		double[] steps = {0.15, 0.3, 0.5};
		for (double step : steps) {
			for (int k = 0; k < 8; k++) {
				double a = k * Math.PI / 4;
				Vec3 moved = body.offset[1].add(Math.cos(a) * step, 0, Math.sin(a) * step);
				if (clear(level, entity.position().add(moved), body, body.angle)) {
					body.offset[1] = moved;
					return;
				}
			}
		}
		// nowhere flat: it leans up where it is until it fits
		for (float angle = body.angle; angle > 0; angle -= 0.15F) {
			if (clear(level, feet, body, angle)) {
				body.angle = body.lastAngle = angle;
				return;
			}
		}
	}

	/**
	 * The way to fall: the way of the blow if there is room to lie down, otherwise the nearest way round that has
	 * room; with room nowhere, the way of the blow, and it leans on whatever is there.
	 */
	private static void pickWay(ClientLevel level, Entity entity, Body body) {
		float dx = body.dx, dz = body.dz;
		double[] turns = {0, 0.6, -0.6, 1.2, -1.2, 1.9, -1.9, Math.PI};
		for (double turn : turns) {
			float cx = (float) (dx * Math.cos(turn) - dz * Math.sin(turn));
			float cz = (float) (dx * Math.sin(turn) + dz * Math.cos(turn));
			body.dx = cx;
			body.dz = cz;
			if (clear(level, entity.position(), body, RIGHT_ANGLE)) return;
		}
		body.dx = dx;
		body.dz = dz;
	}

	/**
	 * In a block that rises above the low ground: a carpet or a slab under the body is not in its way.
	 */
	private static boolean blockedAbove(ClientLevel level, Vec3 at, double feetY) {
		var pos = net.minecraft.core.BlockPos.containing(at.x, at.y, at.z);
		var shape = level.getBlockState(pos).getCollisionShape(level, pos);
		if (shape.isEmpty()) return false;
		return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) > feetY + LOW + 1e-3
				|| pos.getY() + shape.min(net.minecraft.core.Direction.Axis.Y) > at.y;
	}

	/**
	 * While the real player still lies dying the copy moves with it (the knockback of the last blow) and takes its
	 * newest wounds: on Paper they come a tick after the death, a head blown off by the same blast included.
	 */
	private static void follow(ClientLevel level, Body body) {
		body.copy.setOldPosAndRot();
		// only the newest body of a player follows it: it may have died again since
		if (!Objects.equals(HIDDEN.get(body.sourceId), body.entityId)) return;
		Entity real = level.getEntity(body.sourceId);
		if (!(real instanceof LivingEntity living) || !living.isDeadOrDying()) return;
		body.copy.setPos(real.getX(), real.getY(), real.getZ());
		// the wounds of the death only (on Paper the killing blow comes a tick late); later the body has its own, cut
		// by blows at it, and the living player's must not overwrite them
		if (body.age > 10) return;
		WoundState wounds = ClientWounds.get(body.sourceId);
		if (wounds != null) {
			ClientWounds.put(body.entityId, wounds);
			ClientWounds.get(body.entityId);
		}
		// and its cuts: a head off at the death flies from the copy, the real player is not drawn
		ClientWounds.moveDetaches(body.sourceId, body.entityId);
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
			if (body.copy == null || body.copy.distanceToSqr(camera) > 128 * 128) continue;
			try {
				var state = dispatcher.extractEntity(body.copy, partial);
				dispatcher.submit(state, context.levelState().cameraRenderState, state.x - camera.x, state.y - camera.y, state.z - camera.z,
						stack, context.submitNodeCollector());
			} catch (RuntimeException e) {
				// once: it goes on the next tick, not a warning every frame
				if (body.sinkLeft < 0) ChopChop.LOG.warn("Can't draw a dead player, it is left out", e);
				body.sinkLeft = 1;
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
			HIDDEN.remove(body.sourceId, entityId);
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
