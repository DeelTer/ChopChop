package ru.deelter.chopchop.api;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ru.deelter.chopchop.core.api.BodyOps;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;
import ru.deelter.chopchop.server.ChopApiHooks;
import ru.deelter.chopchop.server.ChopServer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The server-side API of ChopChop on Fabric (dedicated server or the singleplayer host). Call it on the server
 * thread only. Every change is sent to the clients that see the entity, as the mod's own wounds are.
 * <p>
 * See {@code API.md} in the repository for a guide.
 */
public final class ChopChopApi {
	private ChopChopApi() {
	}

	/**
	 * A spot on a body.
	 *
	 * @param part  "head", "body", "right_arm", "left_arm", "right_leg", "left_leg" (names depend on the profile)
	 * @param along 0 at the root of the part (shoulder, hip, neck), 1 at its end (hand, foot, top of the head)
	 * @param point the point in the world
	 */
	public record BodySpot(String part, double along, Vec3 point, BodyOps.Spot internal) {
	}

	/**
	 * The shape of a wound made with {@link #wound}.
	 *
	 * @param swing  how the blade moved, in the world, for a cut along it; null for a round hole
	 * @param length length of a cut, in voxels (one voxel is about one pixel of the model)
	 * @param radius radius of a hole, or half the width of a cut, voxels
	 * @param depth  how deep it goes under the skin, voxels. It never comes out on the far side of the part
	 */
	public record WoundShape(@Nullable Vec3 swing, double length, double radius, int depth) {
		public static WoundShape hole(double radius, int depth) {
			return new WoundShape(null, 0, radius, depth);
		}

		public static WoundShape cut(Vec3 swing, double length, double width, int depth) {
			return new WoundShape(swing, length, width / 2, depth);
		}
	}

	/**
	 * The server runs ChopChop and the entity's type has a body profile.
	 */
	public static boolean hasBody(LivingEntity entity) {
		return profile(entity) != null;
	}

	/**
	 * Names of the parts of the entity's body, empty without a profile.
	 */
	public static List<String> parts(LivingEntity entity) {
		BodyProfile profile = profile(entity);
		List<String> names = new ArrayList<>();
		if (profile != null) for (int i = 0; i < profile.getParts().size(); i++) names.add(profile.part(i).getName());
		return names;
	}

	/**
	 * How much of a part is left: 1 whole (or unknown part), 0 cut off at its root.
	 */
	public static double remaining(LivingEntity entity, String part) {
		WoundState state = ChopServer.wounds(entity);
		if (state == null) return 1;
		BodyPart def = state.getProfile().part(part);
		if (def == null) return 1;
		PartState partState = state.part(def.getIndex());
		return partState.severed() ? (double) partState.getSeveredAt() / def.gy() : 1;
	}

	public static boolean isSevered(LivingEntity entity, String part) {
		return remaining(entity, part) < 1;
	}

	public static boolean isHeadless(LivingEntity entity) {
		WoundState state = ChopServer.wounds(entity);
		return state != null && state.headless();
	}

	public static boolean isLegless(LivingEntity entity) {
		WoundState state = ChopServer.wounds(entity);
		return state != null && state.legless();
	}

	/**
	 * The hand of this side ("right", "left") is gone with its arm.
	 */
	public static boolean isHandless(LivingEntity entity, String side) {
		WoundState state = ChopServer.wounds(entity);
		return state != null && state.handless(side);
	}

	/**
	 * Where a ray meets the body, on its rest pose turned like the entity (the server does not know the animation).
	 *
	 * @param nearest when the ray misses the model, take the part nearest to it instead of null
	 */
	public static @Nullable BodySpot locate(LivingEntity target, Vec3 origin, Vec3 direction, double reach, boolean nearest) {
		WoundState state = state(target);
		if (state == null || direction.lengthSqr() < 1e-9) return null;
		Vec3 dir = direction.normalize();
		BodyOps.Spot spot = BodyOps.locate(state, pose(target, state), origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, reach, nearest);
		return spot == null ? null : new BodySpot(spot.part(), spot.along(), new Vec3(spot.world()[0], spot.world()[1], spot.world()[2]), spot);
	}

	/**
	 * Cuts a part off at a place along it: everything from there to its end comes off and falls. A head always comes
	 * off whole. Consequences follow as for a cut of the mod itself: a head kills, no legs crawl, no hand drops what
	 * it holds.
	 *
	 * @param along    0 at the root, 1 at the end of the part
	 * @param attacker who did it: gets the kill of a head and the kill cam; null for none
	 * @param fling    the piece flies off hard, as from a blast
	 * @return whether anything more came off
	 */
	public static boolean sever(LivingEntity target, String part, double along, @Nullable Entity attacker, boolean fling) {
		WoundState state = state(target);
		if (state == null || ChopServer.book() == null) return false;
		boolean wasLegless = state.legless(), wasHeadless = state.headless();
		var messages = BodyOps.sever(ChopServer.book(), target.getUUID(), target.getId(), state, part, along, fling, attacker == null ? -1 : attacker.getId());
		return send(target, state, messages, attacker, wasLegless, wasHeadless);
	}

	/**
	 * Cuts the part of a spot off where the spot is.
	 */
	public static boolean sever(LivingEntity target, BodySpot spot, @Nullable Entity attacker, boolean fling) {
		return sever(target, spot.part(), spot.along(), attacker, fling);
	}

	/**
	 * Opens a wound at a spot. It ignores the chances and size limits in the server rules: the shape is yours. It
	 * never goes through to the far side of the part.
	 *
	 * @return whether anything was carved
	 */
	public static boolean wound(LivingEntity target, BodySpot spot, WoundShape shape, @Nullable Entity attacker) {
		WoundState state = state(target);
		if (state == null || ChopServer.book() == null) return false;
		double[] swing = shape.swing() == null ? null : new double[]{shape.swing().x, shape.swing().y, shape.swing().z};
		var messages = BodyOps.wound(ChopServer.book(), target.getUUID(), target.getId(), state, pose(target, state), spot.internal(), swing,
				shape.length(), shape.radius(), shape.depth(), attacker == null ? -1 : attacker.getId());
		return send(target, state, messages, attacker, state.legless(), state.headless());
	}

	/**
	 * Makes the entity whole again.
	 */
	public static void heal(LivingEntity entity) {
		ChopServer.heal(entity);
	}

	/**
	 * Decides which damage ChopChop turns into wounds on its own. Return false to leave a hit to your mod (and call
	 * {@link #sever} / {@link #wound} yourself); null puts the default back (everything).
	 */
	public static void setAutomaticWounds(@Nullable BiPredicate<LivingEntity, DamageSource> filter) {
		ChopApiHooks.setAutomatic(filter);
	}

	private static boolean send(LivingEntity target, WoundState state, List<byte[]> messages, @Nullable Entity attacker,
								boolean wasLegless, boolean wasHeadless) {
		if (messages.isEmpty()) return false;
		for (byte[] message : messages) ChopServer.broadcastApi(target, message);
		var source = attacker instanceof LivingEntity living
				? target.damageSources().mobAttack(living) : target.damageSources().generic();
		if (attacker instanceof net.minecraft.world.entity.player.Player player) source = target.damageSources().playerAttack(player);
		ChopServer.consequences(target, source, state, wasLegless, wasHeadless);
		return true;
	}

	private static @Nullable BodyProfile profile(LivingEntity entity) {
		var profiles = ChopServer.profilesApi();
		return profiles == null ? null : profiles.forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
	}

	private static @Nullable WoundState state(LivingEntity entity) {
		BodyProfile profile = profile(entity);
		if (profile == null || ChopServer.book() == null) return null;
		return ChopServer.book().getOrBlank(entity.getUUID(), profile);
	}

	private static BodyPose pose(LivingEntity entity, WoundState state) {
		return ChopServer.pose(entity, state);
	}
}
