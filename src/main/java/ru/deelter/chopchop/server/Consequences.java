package ru.deelter.chopchop.server;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.*;
import java.util.List;

/**
 * What a body that lost its legs or its head does on a Fabric server:
 * <ul>
 *     <li>no legs: a mob gets slow (the client draws it lying), a player crawls in the vanilla pose;</li>
 *     <li>no head: a player stands a moment, out of control, and falls dead; a mob runs around blind for a few
 *     seconds and then dies.
 *     The kill is credited to whoever took the head.</li>
 * </ul>
 * The Paper plugin does the same with Bukkit calls.
 */
final class Consequences {
	static final Identifier CRAWL_SPEED = Identifier.fromNamespaceAndPath(ChopChop.ID, "crawl");

	private record Headless(LivingEntity entity, DamageSource source, int[] ticks) {
	}

	private static final Map<UUID, Headless> HEADLESS = new HashMap<>();
	/**
	 * set while a headless body is killed by the mod itself
	 */
	static boolean finishing;
	/**
	 * mobs held still while a kill cam plays: ticks left and whether they had no AI before
	 */
	private static final Map<Mob, int[]> FROZEN = new HashMap<>();

	private Consequences() {
	}

	/**
	 * Kills an entity with a blow of the mod itself: no new wounds from it, the kill goes to the source.
	 */
	public static void finish(LivingEntity entity, ServerLevel level, DamageSource source) {
		finishing = true;
		try {
			if (!entity.hurtServer(level, source, Float.MAX_VALUE) && entity.isAlive()) entity.kill(level);
		} finally {
			finishing = false;
		}
		if (entity.isAlive()) entity.kill(level);
	}

	/**
	 * After a hit changed a body.
	 */
	static void apply(LivingEntity entity, DamageSource source, WoundState state, boolean wasLegless, boolean wasHeadless, WoundRules rules) {
		// lying mobs need nothing here, their AI is skipped (MobAiMixin); crawling ones slow down
		if (!wasLegless && BodyPose.crawls(state, rules) && !(entity instanceof Player) && !rules.mobsLie()) slow(entity, rules);
		if (!wasHeadless && state.headless() && rules.isDecapitationKills() && entity.isAlive()) {
			double seconds = entity instanceof Player ? rules.getHeadlessPlayerSeconds() : entity instanceof Mob ? rules.getHeadlessSeconds() : 0;
			int ticks = (int) Math.round(seconds * 20);
			HEADLESS.put(entity.getUUID(), new Headless(entity, source, new int[]{Math.max(1, ticks)}));
		}
		if (entity instanceof Mob mob && rules.isDisarmMobs()) disarm(mob, state, rules);
		limp(entity, state, rules);
	}

	/**
	 * A hand that is gone holds nothing: a skeleton without its bow arm has no bow to shoot.
	 */
	static void disarm(Mob mob, WoundState state, WoundRules rules) {
		boolean rightMain = mob.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT;
		drop(mob, net.minecraft.world.entity.EquipmentSlot.MAINHAND, state.handless(rightMain ? "right" : "left"), rules);
		drop(mob, net.minecraft.world.entity.EquipmentSlot.OFFHAND, state.handless(rightMain ? "left" : "right"), rules);
		if (state.armless()) mob.setTarget(null);
	}

	private static void drop(Mob mob, net.minecraft.world.entity.EquipmentSlot slot, boolean gone, WoundRules rules) {
		if (!gone) return;
		net.minecraft.world.item.ItemStack stack = mob.getItemBySlot(slot);
		if (stack.isEmpty()) return;
		mob.setItemSlot(slot, net.minecraft.world.item.ItemStack.EMPTY);
		if (rules.isDisarmDrops() && mob.level() instanceof ServerLevel level) mob.spawnAtLocation(level, stack);
	}

	/**
	 * A mob without the hand in question, see {@code MobArmsMixin}.
	 */
	static boolean noHands(Mob mob) {
		WoundState state = ChopServer.wounds(mob);
		WoundRules rules = ChopServer.rules();
		return state != null && rules != null && rules.isDisarmMobs() && state.armless();
	}

	static boolean noMainHand(Mob mob) {
		WoundState state = ChopServer.wounds(mob);
		WoundRules rules = ChopServer.rules();
		if (state == null || rules == null || !rules.isDisarmMobs()) return false;
		return state.handless(mob.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? "right" : "left");
	}

	static void tick(MinecraftServer server) {
		keepLimping(server);
		if (!FROZEN.isEmpty()) {
			Iterator<Map.Entry<Mob, int[]>> frozen = FROZEN.entrySet().iterator();
			while (frozen.hasNext()) {
				var e = frozen.next();
				if (--e.getValue()[0] > 0 && e.getKey().isAlive()) continue;
				e.getKey().setNoAi(e.getValue()[1] == 1);
				frozen.remove();
			}
		}
		if (HEADLESS.isEmpty()) return;
		Iterator<Headless> it = HEADLESS.values().iterator();
		while (it.hasNext()) {
			Headless h = it.next();
			LivingEntity entity = h.entity;
			if (!entity.isAlive() || entity.isRemoved()) {
				it.remove();
				continue;
			}
			if (entity instanceof Mob mob) {
				// no head, no eyes: forget the target and stagger around
				mob.setTarget(null);
				if (mob instanceof PathfinderMob walker && (h.ticks[0] % 10 == 0 || mob.getNavigation().isDone())) {
					Vec3 to = DefaultRandomPos.getPos(walker, 5, 2);
					if (to != null) mob.getNavigation().moveTo(to.x, to.y, to.z, 1.3);
				}
			}
			if (--h.ticks[0] > 0) continue;
			it.remove();
			if (entity.level() instanceof ServerLevel level) {
				// the source of the chop, so the kill and its message go to whoever did it. More than any hit
				// before it, so invulnerability frames do not swallow it
				// the mod's own finishing blow: no new wounds from it, it is not a blow anyone struck
				finishing = true;
				try {
					if (!entity.hurtServer(level, h.source, Float.MAX_VALUE) && entity.isAlive()) entity.kill(level);
				} finally {
					finishing = false;
				}
			}
		}
	}

	/**
	 * Monsters near the killer that are after it: during a kill cam they could kill it unseen.
	 */
	static List<Mob> threats(Player killer, LivingEntity victim, WoundRules rules) {
		double r = rules.getKillCamThreatRadius();
		// not only the ones after the killer right now: any monster near that sees it, and whoever hit it a moment ago
		LivingEntity lastHit = killer.getLastHurtByMob();
		boolean justHit = lastHit != null && killer.tickCount - killer.getLastHurtByMobTimestamp() < 60;
		return killer.level().getEntitiesOfClass(Mob.class, killer.getBoundingBox().inflate(r),
				mob -> mob != victim && mob.isAlive() && mob.distanceToSqr(killer) <= r * r
						&& (mob.getTarget() == killer || justHit && mob == lastHit
						|| mob instanceof net.minecraft.world.entity.monster.Enemy && mob.hasLineOfSight(killer)));
	}

	/**
	 * Holds them still for the kill cam; the AI comes back after, as it was.
	 */
	static void freeze(List<Mob> mobs, WoundRules rules) {
		int ticks = (int) Math.round(rules.getKillCamFreezeSeconds() * 20);
		for (Mob mob : mobs) {
			int[] f = FROZEN.computeIfAbsent(mob, m -> new int[]{0, m.isNoAi() ? 1 : 0});
			f[0] = Math.max(f[0], ticks);
			mob.setNoAi(true);
		}
	}

	static void clear(LivingEntity entity) {
		stopLimping(entity);
		HEADLESS.remove(entity.getUUID());
		AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(CRAWL_SPEED);
		}
	}

	/**
	 * A body that grew back: no limp without a lost leg, no crawl with a leg to stand on.
	 */
	static void regrown(LivingEntity entity, WoundState state) {
		if (state == null) {
			clear(entity);
			return;
		}
		if (state.legsGone() == 0) stopLimping(entity);
		if (!state.legless()) {
			AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speed != null) speed.removeModifier(CRAWL_SPEED);
		}
	}

	static void reset() {
		HEADLESS.clear();
		FROZEN.forEach((mob, f) -> mob.setNoAi(f[1] == 1));
		FROZEN.clear();
	}

	/**
	 * A leg cut off: Slowness, with its icon, for as long as the leg is gone. Endless and quiet (no particles); put
	 * back by {@link #keepLimping} when milk or a command takes it. A respawned player starts whole and without it.
	 */
	static void limp(LivingEntity entity, WoundState state, WoundRules rules) {
		if (state == null || state.legsGone() == 0 || rules.getLimpSlowness() <= 0 || !entity.isAlive()) return;
		var current = entity.getEffect(MobEffects.SLOWNESS);
		int amplifier = rules.getLimpSlowness() - 1;
		if (current != null && current.getAmplifier() >= amplifier) return;
		entity.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, MobEffectInstance.INFINITE_DURATION, amplifier, true, false, true));
	}

	/**
	 * Every two seconds: players without a leg still limp.
	 */
	static void keepLimping(MinecraftServer server) {
		if (server.getTickCount() % 40 != 0) return;
		WoundRules rules = ChopServer.rules();
		if (rules == null || rules.getLimpSlowness() <= 0) return;
		for (var player : server.getPlayerList().getPlayers()) limp(player, ChopServer.wounds(player), rules);
	}

	private static void stopLimping(LivingEntity entity) {
		var current = entity.getEffect(MobEffects.SLOWNESS);
		// only the endless one of a lost leg, not a potion or an arrow
		if (current != null && current.isInfiniteDuration() && current.isAmbient()) entity.removeEffect(MobEffects.SLOWNESS);
	}

	private static void slow(LivingEntity entity, WoundRules rules) {
		AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null || speed.hasModifier(CRAWL_SPEED)) return;
		// transient: wounds of mobs are not saved, a slowness that is would outlive them
		speed.addTransientModifier(new AttributeModifier(CRAWL_SPEED, rules.getCrawlSpeed() - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}
}
