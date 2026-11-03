package ru.deelter.chopchop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.client.render.AnimatedPose;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.wound.WoundState;
import ru.deelter.chopchop.net.ChopPayload;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The crosshair finds bodies by their model, as drawn, and tells the server where the blow landed.
 * <ul>
 *     <li>Right after the vanilla pick, on a server with ChopChop: the bodies within reach are cast against the pose
 *     they were last drawn in ({@link AnimatedPose}): a swinging arm is hit where it is, and arms that reach out of
 *     the collision box (a zombie's) can be hit at all. Without a fresh pose, against the rest pose like the server.</li>
 *     <li>When vanilla found no entity and a body is under the crosshair, that body becomes the target. The attack is
 *     the vanilla one: the server accepts it while the target's box is within reach plus the vanilla margin.</li>
 *     <li>Right before the attack the spot goes to the server ({@link Protocol#C_AIM}), which checks it against its rest
 *     pose with room for the animation ({@code AimCheck}) and uses it, or finds the spot itself as before.</li>
 * </ul>
 */
public final class ModelPick {
	private static final long AIM_NANOS = 250_000_000L;
	/**
	 * a whole body of each profile, to cast against entities without wounds; never changed
	 */
	private static final Map<BodyProfile, WoundState> WHOLE = new IdentityHashMap<>();
	private static Spot last;
	/**
	 * a dead body under the crosshair, nearer than any block: a click cuts it instead of hitting what is behind
	 */
	private static Spot corpse;
	private static long lastCutNanos;
	private static final long CUT_NANOS = 400_000_000L;

	private record Spot(int entityId, Hit hit, long nanos) {
	}

	private record Found(LivingEntity entity, Hit hit, Vec3 point) {
	}

	private ModelPick() {
	}

	public static void afterPick(Minecraft minecraft, float partial) {
		last = null;
		corpse = null;
		if (!ChopChopClient.serverSpeaks() || minecraft.player == null || minecraft.level == null) return;
		Entity viewer = minecraft.getCameraEntity();
		if (viewer == null || viewer != minecraft.player || minecraft.player.isSpectator()) return;
		HitResult current = minecraft.hitResult;
		double reach = minecraft.player.entityInteractionRange();
		Vec3 eye = viewer.getEyePosition(partial);
		Vec3 look = viewer.getViewVector(partial);
		Found found;
		if (current instanceof EntityHitResult entityHit) {
			// vanilla found it by its box: only where on the body
			if (!(entityHit.getEntity() instanceof LivingEntity living)) return;
			found = cast(living, eye, look, reach + 1, partial);
		} else {
			// a block in the way stops the ray where vanilla stopped
			double limit = current != null && current.getType() != HitResult.Type.MISS ? Math.min(reach, current.getLocation().distanceTo(eye)) : reach;
			found = null;
			AABB around = viewer.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.5);
			for (LivingEntity entity : minecraft.level.getEntitiesOfClass(LivingEntity.class, around,
					e -> e != viewer && e.isPickable() && !e.isSpectator() && !e.isInvisible())) {
				Found f = cast(entity, eye, look, found == null ? limit : found.hit().depth(), partial);
				if (f != null) found = f;
			}
			if (found != null) {
				minecraft.hitResult = new EntityHitResult(found.entity(), found.point());
				minecraft.crosshairPickEntity = found.entity();
			} else {
				pickCorpse(minecraft, eye, look, limit);
			}
		}
		if (found != null) last = new Spot(found.entity().getId(), found.hit(), System.nanoTime());
	}

	/**
	 * Dead bodies within reach, on the pose they were drawn in (they lie, the rest pose would be of no use). One under
	 * the crosshair takes it: no block behind it is hit or broken.
	 */
	private static void pickCorpse(Minecraft minecraft, Vec3 eye, Vec3 look, double limit) {
		if (Corpses.size() == 0) return;
		Found best = null;
		for (Corpses.Body body : Corpses.all()) {
			if (!(Corpses.entity(body.id()) instanceof LivingEntity entity) || entity.distanceToSqr(eye) > (limit + 3) * (limit + 3)) continue;
			BodyProfile profile = ChopChop.getProfiles().forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			AnimatedPose.Snapshot drawn = AnimatedPose.get(body.id());
			if (profile == null || drawn == null || drawn.profile() != profile) continue;
			WoundState wounds = ClientWounds.get(body.id());
			WoundState state = wounds != null && wounds.getProfile() == profile ? wounds : WHOLE.computeIfAbsent(profile, WoundState::new);
			Found f = castDrawn(entity, state, drawn, eye, look, best == null ? limit : best.hit().depth());
			if (f != null) best = f;
		}
		if (best == null) return;
		corpse = new Spot(best.entity().getId(), best.hit(), System.nanoTime());
		minecraft.hitResult = net.minecraft.world.phys.BlockHitResult.miss(best.point(), net.minecraft.core.Direction.UP,
				net.minecraft.core.BlockPos.containing(best.point()));
	}

	/**
	 * A click with a dead body under the crosshair: the blow goes to the server, which cuts the body for everyone.
	 *
	 * @return true when the click was spent on a body
	 */
	public static boolean cutCorpse(Minecraft minecraft) {
		Spot spot = corpse;
		if (spot == null || minecraft.player == null || System.nanoTime() - spot.nanos() > AIM_NANOS) return false;
		if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(ChopPayload.TYPE)) return false;
		// the swing is seen at once; blows faster than a sword swings are not sent
		minecraft.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		long now = System.nanoTime();
		if (now - lastCutNanos < CUT_NANOS) return true;
		lastCutNanos = now;
		minecraft.player.resetAttackStrengthTicker();
		Hit hit = spot.hit();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new ChopPayload(Protocol.cut(spot.entityId(), hit.part(), hit.x(), hit.y(), hit.z(), hit.inward(), hit.slash())));
		return true;
	}

	/**
	 * Before an attack: where it lands, for the server.
	 */
	public static void beforeAttack(Entity target) {
		Spot spot = last;
		if (spot == null || target == null || spot.entityId() != target.getId() || System.nanoTime() - spot.nanos() > AIM_NANOS) return;
		if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(ChopPayload.TYPE)) return;
		Hit hit = spot.hit();
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new ChopPayload(Protocol.aim(spot.entityId(), hit.part(), hit.x(), hit.y(), hit.z(), hit.inward(), hit.slash())));
	}

	/**
	 * The nearest spot of a body along the ray, on the pose it was drawn in, or on the rest pose.
	 */
	private static Found cast(LivingEntity entity, Vec3 eye, Vec3 look, double maxT, float partial) {
		BodyProfile profile = ChopChop.getProfiles().forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		if (profile == null) return null;
		WoundState wounds = ClientWounds.get(entity.getId());
		WoundState state = wounds != null && wounds.getProfile() == profile ? wounds : WHOLE.computeIfAbsent(profile, WoundState::new);
		AnimatedPose.Snapshot drawn = AnimatedPose.get(entity.getId());
		if (drawn != null && drawn.profile() == profile) return castDrawn(entity, state, drawn, eye, look, maxT);
		Vec3 at = entity.getPosition(partial);
		float yaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
		BodyPose pose = new BodyPose(at.x, at.y, at.z, yaw, entity.getScale(), 0).crawling(state, ChopChopClient.serverRules());
		Hit hit = HitLocator.cast(state, pose, eye.x, eye.y, eye.z, look.x, look.y, look.z, maxT);
		if (hit == null || hit.depth() >= maxT) return null;
		var world = HitLocator.toWorld(pose, hit.model()[0], hit.model()[1], hit.model()[2]);
		return new Found(entity, hit, new Vec3(world.x, world.y, world.z));
	}

	/**
	 * Each part as drawn: the ray goes into the frame of the part (blocks), to its pixels, then into the rest model
	 * space where the part sits as the profile has it, and is cast there. All the steps are affine, so the distance
	 * along the ray stays the distance in the world.
	 */
	private static Found castDrawn(LivingEntity entity, WoundState state, AnimatedPose.Snapshot drawn, Vec3 eye, Vec3 look, double maxT) {
		BodyProfile profile = drawn.profile();
		double[] cam = drawn.camera();
		Hit best = null;
		Vector3d bestWorld = null;
		for (int i = 0; i < drawn.parts().length && i < state.partCount(); i++) {
			Matrix4f partToCamera = drawn.parts()[i];
			if (partToCamera == null) continue;
			BodyPart part = profile.part(i);
			if (!part.isWoundable() && !part.severable()) continue;
			// world (camera relative) -> part, blocks -> pixels -> rest model space
			Matrix4d toRest = HitLocator.partToModel(profile, part).scale(16).mul(new Matrix4d(partToCamera).invert());
			Vector3d origin = toRest.transformPosition(new Vector3d(eye.x - cam[0], eye.y - cam[1], eye.z - cam[2]));
			Vector3d dir = toRest.transformDirection(new Vector3d(look.x, look.y, look.z));
			Vector3d up = toRest.transformDirection(new Vector3d(0, 1, 0));
			Hit hit = HitLocator.castOnePart(profile, part, state.part(i), origin, dir, up, best == null ? maxT : best.depth());
			if (hit == null || best != null && hit.depth() >= best.depth()) continue;
			best = hit;
			bestWorld = new Vector3d(eye.x + look.x * hit.depth(), eye.y + look.y * hit.depth(), eye.z + look.z * hit.depth());
		}
		return best == null ? null : new Found(entity, best, new Vec3(bestWorld.x, bestWorld.y, bestWorld.z));
	}
}
