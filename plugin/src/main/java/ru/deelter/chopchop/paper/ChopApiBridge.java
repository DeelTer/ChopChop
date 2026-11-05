package ru.deelter.chopchop.paper;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import ru.deelter.chopchop.core.api.BodyOps;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * The plugin side of {@link ru.deelter.chopchop.paper.api.ChopChopApi}. Not an API itself.
 */
public final class ChopApiBridge {
	private ChopApiBridge() {
	}

	private static ChopPlugin plugin() {
		ChopPlugin plugin = ChopPlugin.instance();
		if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException("ChopChop is not enabled");
		return plugin;
	}

	private static BodyProfile profile(LivingEntity entity) {
		return plugin().profiles().forEntity(entity.getType().getKey().toString());
	}

	private static WoundState state(LivingEntity entity) {
		BodyProfile profile = profile(entity);
		return profile == null ? null : plugin().book().getOrBlank(entity.getUniqueId(), profile);
	}

	public static boolean hasBody(LivingEntity entity) {
		return profile(entity) != null;
	}

	public static List<String> parts(LivingEntity entity) {
		BodyProfile profile = profile(entity);
		List<String> names = new ArrayList<>();
		if (profile != null) for (int i = 0; i < profile.getParts().size(); i++) names.add(profile.part(i).getName());
		return names;
	}

	public static double remaining(LivingEntity entity, String part) {
		WoundState state = plugin().book().get(entity.getUniqueId());
		if (state == null) return 1;
		BodyPart def = state.getProfile().part(part);
		if (def == null) return 1;
		PartState partState = state.part(def.getIndex());
		return partState.severed() ? (double) partState.getSeveredAt() / def.gy() : 1;
	}

	public static byte[] stateMessage(LivingEntity entity) {
		WoundState state = plugin().book().get(entity.getUniqueId());
		return state == null ? null : ru.deelter.chopchop.core.net.Protocol.state(entity.getEntityId(), state);
	}

	public static boolean headless(LivingEntity entity) {
		WoundState state = plugin().book().get(entity.getUniqueId());
		return state != null && state.headless();
	}

	public static java.util.Map<String, Object> woundsOf(java.util.UUID entity) {
		WoundState state = plugin().book().get(entity);
		return state == null ? null : ru.deelter.chopchop.core.api.BodyOps.snapshot(state);
	}

	public static boolean legless(LivingEntity entity) {
		WoundState state = plugin().book().get(entity.getUniqueId());
		return state != null && state.legless();
	}

	public static boolean handless(LivingEntity entity, String side) {
		WoundState state = plugin().book().get(entity.getUniqueId());
		return state != null && state.handless(side);
	}

	public static BodyOps.Spot locate(LivingEntity target, Location origin, Vector direction, double reach, boolean nearest) {
		WoundState state = state(target);
		if (state == null || direction.lengthSquared() < 1e-9) return null;
		Vector dir = direction.clone().normalize();
		return BodyOps.locate(state, plugin().poseOf(target, state), origin.getX(), origin.getY(), origin.getZ(),
				dir.getX(), dir.getY(), dir.getZ(), reach, nearest);
	}

	public static boolean sever(LivingEntity target, String part, double along, Entity attacker, boolean fling) {
		WoundState state = state(target);
		if (state == null) return false;
		boolean wasLegless = state.legless(), wasHeadless = state.headless();
		var messages = BodyOps.sever(plugin().book(), target.getUniqueId(), target.getEntityId(), state, part, along, fling,
				attacker == null ? -1 : attacker.getEntityId());
		return send(target, state, messages, attacker, wasLegless, wasHeadless);
	}

	public static boolean halve(LivingEntity target, double along, Entity attacker, boolean fling) {
		WoundState state = state(target);
		if (state == null) return false;
		boolean wasLegless = state.legless(), wasHeadless = state.headless();
		var messages = BodyOps.halve(plugin().book(), target.getUniqueId(), target.getEntityId(), state, along, fling,
				attacker == null ? -1 : attacker.getEntityId());
		if (!send(target, state, messages, attacker, wasLegless, wasHeadless)) return false;
		if (!target.isDead()) plugin().finishApi(target, attacker);
		return true;
	}

	public static boolean split(LivingEntity target, Entity attacker, boolean fling) {
		WoundState state = state(target);
		if (state == null) return false;
		boolean wasLegless = state.legless(), wasHeadless = state.headless();
		var messages = BodyOps.split(plugin().book(), target.getUniqueId(), target.getEntityId(), state, fling,
				attacker == null ? -1 : attacker.getEntityId());
		if (!send(target, state, messages, attacker, wasLegless, wasHeadless)) return false;
		if (!target.isDead()) plugin().finishApi(target, attacker);
		return true;
	}

	public static boolean wound(LivingEntity target, BodyOps.Spot spot, double[] swing, double length, double radius, int depth, Entity attacker) {
		WoundState state = state(target);
		if (state == null) return false;
		var messages = BodyOps.wound(plugin().book(), target.getUniqueId(), target.getEntityId(), state, plugin().poseOf(target, state), spot,
				swing, length, radius, depth, attacker == null ? -1 : attacker.getEntityId());
		return send(target, state, messages, attacker, state.legless(), state.headless());
	}

	public static java.util.Map<Integer, Location> corpses(org.bukkit.World world, org.bukkit.util.BoundingBox area) {
		return plugin().corpsesApi(world, area);
	}

	public static boolean changeCorpse(int id, String part, double along, boolean fling, double[] wound) {
		return plugin().changeCorpseApi(id, part, along, fling, wound);
	}

	public static void heal(LivingEntity entity) {
		plugin().healApi(entity);
	}

	public static void setAutomatic(BiPredicate<LivingEntity, EntityDamageEvent> filter) {
		plugin().setAutomatic(filter);
	}

	private static boolean send(LivingEntity target, WoundState state, List<byte[]> messages, Entity attacker, boolean wasLegless,
								boolean wasHeadless) {
		if (messages.isEmpty()) return false;
		for (byte[] message : messages) plugin().broadcastApi(target, message);
		plugin().consequencesApi(target, attacker, state, wasLegless, wasHeadless);
		return true;
	}
}
