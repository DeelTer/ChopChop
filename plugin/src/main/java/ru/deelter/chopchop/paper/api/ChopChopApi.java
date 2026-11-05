package ru.deelter.chopchop.paper.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import ru.deelter.chopchop.core.api.BodyOps;
import ru.deelter.chopchop.paper.ChopApiBridge;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The API of the ChopChop Paper plugin. Main thread only. Every change is sent to the players with the mod who see
 * the entity. The same calls as the Fabric {@code ru.deelter.chopchop.api.ChopChopApi}, with Bukkit types.
 * <p>
 * See {@code API.md} in the repository for a guide. Add {@code depend: [ChopChop]} (or {@code softdepend}) to your
 * plugin.yml.
 */
public final class ChopChopApi {
	private ChopChopApi() {
	}

	/**
	 * @param part  "head", "body", "right_arm", "left_arm", "right_leg", "left_leg" (names depend on the profile)
	 * @param along 0 at the root of the part (shoulder, hip, neck), 1 at its end
	 */
	public record BodySpot(String part, double along, Location point, BodyOps.Spot internal) {
	}

	/**
	 * @param swing  how the blade moved, in the world, for a cut along it; null for a round hole
	 * @param length length of a cut, voxels (about one pixel of the model each)
	 * @param radius radius of a hole, or half the width of a cut, voxels
	 * @param depth  voxels under the skin; a wound never comes out on the far side of the part
	 */
	public record WoundShape(@Nullable Vector swing, double length, double radius, int depth) {
		public static WoundShape hole(double radius, int depth) {
			return new WoundShape(null, 0, radius, depth);
		}

		public static WoundShape cut(Vector swing, double length, double width, int depth) {
			return new WoundShape(swing, length, width / 2, depth);
		}
	}

	public static boolean hasBody(LivingEntity entity) {
		return ChopApiBridge.hasBody(entity);
	}

	public static List<String> parts(LivingEntity entity) {
		return ChopApiBridge.parts(entity);
	}

	/**
	 * 1 whole (or unknown part), 0 cut off at its root.
	 */
	public static double remaining(LivingEntity entity, String part) {
		return ChopApiBridge.remaining(entity, part);
	}

	public static boolean isSevered(LivingEntity entity, String part) {
		return remaining(entity, part) < 1;
	}

	/**
	 * The wounds of an entity as the server would send them to a client with the mod ({@code chopchop:sync}, a full
	 * state message for its entity id), null when it is whole. For plugins that record or relay what clients see,
	 * e.g. a replay that has to start from the wounds an entity already had.
	 */
	public static byte @Nullable [] stateMessage(LivingEntity entity) {
		return ChopApiBridge.stateMessage(entity);
	}

	public static boolean isHeadless(LivingEntity entity) {
		return ChopApiBridge.headless(entity);
	}

	/**
	 * The wounds of an entity by its UUID, as plain Java values only, for a plugin that calls this through reflection
	 * without depending on ChopChop. The keys are listed in {@code API.md}; null when the entity is whole or the
	 * server knows nothing of it.
	 */
	public static java.util.@Nullable Map<String, Object> woundsOf(java.util.UUID entity) {
		return ChopApiBridge.woundsOf(entity);
	}

	/**
	 * The wounds of every player online that has any, by UUID, plain Java values as in {@link #woundsOf}. Players
	 * that are whole are left out.
	 */
	public static java.util.Map<java.util.UUID, java.util.Map<String, Object>> playerWounds() {
		java.util.Map<java.util.UUID, java.util.Map<String, Object>> all = new java.util.LinkedHashMap<>();
		for (org.bukkit.entity.Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
			java.util.Map<String, Object> wounds = woundsOf(player.getUniqueId());
			if (wounds != null) all.put(player.getUniqueId(), wounds);
		}
		return all;
	}

	public static boolean isLegless(LivingEntity entity) {
		return ChopApiBridge.legless(entity);
	}

	public static boolean isHandless(LivingEntity entity, String side) {
		return ChopApiBridge.handless(entity, side);
	}

	public static @Nullable BodySpot locate(LivingEntity target, Location origin, Vector direction, double reach, boolean nearest) {
		BodyOps.Spot spot = ChopApiBridge.locate(target, origin, direction, reach, nearest);
		if (spot == null) return null;
		return new BodySpot(spot.part(), spot.along(), new Location(target.getWorld(), spot.world()[0], spot.world()[1], spot.world()[2]), spot);
	}

	/**
	 * Cuts a part off at {@code along} (0 root, 1 end); a head comes off whole. The usual consequences follow.
	 *
	 * @return whether anything more came off
	 */
	public static boolean sever(LivingEntity target, String part, double along, @Nullable Entity attacker, boolean fling) {
		return ChopApiBridge.sever(target, part, along, attacker, fling);
	}

	/**
	 * A fatality: cuts the body in two at the waist. The trunk is cut at {@code along} (0 neck, 1 hips, 0.5 the
	 * middle) and both legs at the hips; the lower half falls off and the entity dies, the kill going to the
	 * attacker. Meant for a finishing blow of a combat plugin (a falling cleave, an execution).
	 *
	 * @param fling the lower half flies off hard, as from a blast
	 * @return whether the body was cut (false without a profile or a part named "body")
	 */
	public static boolean halve(LivingEntity target, double along, @Nullable Entity attacker, boolean fling) {
		return ChopApiBridge.halve(target, along, attacker, fling);
	}

	public static boolean halve(LivingEntity target, @Nullable Entity attacker) {
		return halve(target, 0.5, attacker, false);
	}

	/**
	 * A fatality: cuts the body in two from the top of the head down, along its own left-right axis (an overhead
	 * blow). The right halves of the head and the trunk and the whole right arm and leg fall off, the left half
	 * stays as the body. The entity dies, the kill going to the attacker.
	 *
	 * @param fling the right half is thrown hard to its side
	 * @return whether the body was cut (false without a profile with parts named "head" and "body")
	 */
	public static boolean split(LivingEntity target, @Nullable Entity attacker, boolean fling) {
		return ChopApiBridge.split(target, attacker, fling);
	}

	public static boolean sever(LivingEntity target, BodySpot spot, @Nullable Entity attacker, boolean fling) {
		return sever(target, spot.part(), spot.along(), attacker, fling);
	}

	/**
	 * Opens a wound at a spot, whatever the chances in the config.
	 *
	 * @return whether anything was carved
	 */
	public static boolean wound(LivingEntity target, BodySpot spot, WoundShape shape, @Nullable Entity attacker) {
		double[] swing = shape.swing() == null ? null : new double[]{shape.swing().getX(), shape.swing().getY(), shape.swing().getZ()};
		return ChopApiBridge.wound(target, spot.internal(), swing, shape.length(), shape.radius(), shape.depth(), attacker);
	}

	/**
	 * Dead bodies lying in a world, by the id they go by, with where they fell (they fall and slide on the clients, so
	 * a block or two off).
	 */
	public static java.util.Map<Integer, Location> corpses(org.bukkit.World world, org.bukkit.util.BoundingBox area) {
		return ChopApiBridge.corpses(world, area);
	}

	/**
	 * Cuts a dead body: the part off at {@code along}, falling as a piece. Only for the eye.
	 */
	public static boolean severCorpse(int corpseId, String part, double along, boolean fling) {
		return ChopApiBridge.changeCorpse(corpseId, part, along, fling, null);
	}

	/**
	 * A wound in a dead body on the front of a part at {@code along}; a cut runs along the part.
	 */
	public static boolean woundCorpse(int corpseId, String part, double along, WoundShape shape) {
		double half = shape.swing() == null ? 0 : shape.length() / 2;
		return ChopApiBridge.changeCorpse(corpseId, part, along, false, new double[]{half, shape.radius(), shape.depth()});
	}

	public static void heal(LivingEntity entity) {
		ChopApiBridge.heal(entity);
	}

	/**
	 * Which damage the plugin turns into wounds on its own; false leaves it to you. Null: everything (the default).
	 */
	public static void setAutomaticWounds(@Nullable BiPredicate<LivingEntity, EntityDamageEvent> filter) {
		ChopApiBridge.setAutomatic(filter);
	}
}
