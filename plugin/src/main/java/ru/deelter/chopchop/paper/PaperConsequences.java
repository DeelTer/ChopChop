package ru.deelter.chopchop.paper;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The same consequences as the Fabric server: no legs, a mob slows down and lies (drawn by the clients), a player
 * crawls in the vanilla pose; no head, a player dies at once and a mob runs around blind first. The kill goes to
 * whoever took the head.
 */
final class PaperConsequences {
	private final JavaPlugin plugin;
	private final NamespacedKey crawlKey;
	private final Set<UUID> headless = new HashSet<>();
	/**
	 * set while a headless body is killed by the plugin itself
	 */
	boolean finishing;
	private final Set<Mob> frozen = new HashSet<>();
	/**
	 * mobs lying without legs, their AI off until they are whole again or leave the world
	 */
	private final Set<Mob> lying = new HashSet<>();

	PaperConsequences(JavaPlugin plugin) {
		this.plugin = plugin;
		this.crawlKey = new NamespacedKey(plugin, "crawl");
	}

	/**
	 * A hand that is gone holds nothing: a skeleton without its bow arm has no bow to shoot.
	 */
	private void disarm(Mob mob, WoundState state, WoundRules rules) {
		var equipment = mob.getEquipment();
		if (equipment == null) return;
		String main = mob.isLeftHanded() ? "left" : "right", off = mob.isLeftHanded() ? "right" : "left";
		if (state.handless(main)) drop(mob, equipment.getItemInMainHand(), rules, () -> equipment.setItemInMainHand(null));
		if (state.handless(off)) drop(mob, equipment.getItemInOffHand(), rules, () -> equipment.setItemInOffHand(null));
		if (state.armless()) mob.setTarget(null);
	}

	private static void drop(Mob mob, org.bukkit.inventory.ItemStack stack, WoundRules rules, Runnable take) {
		if (stack == null || stack.getType().isAir()) return;
		org.bukkit.inventory.ItemStack copy = stack.clone();
		take.run();
		if (rules.isDisarmDrops()) mob.getWorld().dropItemNaturally(mob.getLocation(), copy);
	}

	boolean armless(Mob mob, WoundState state, WoundRules rules) {
		return state != null && rules.isDisarmMobs() && state.armless();
	}

	boolean mainHandless(Mob mob, WoundState state, WoundRules rules) {
		return state != null && rules.isDisarmMobs() && state.handless(mob.isLeftHanded() ? "left" : "right");
	}

	void apply(LivingEntity entity, Entity attacker, WoundState state, boolean wasLegless, boolean wasHeadless, WoundRules rules) {
		if (entity instanceof Mob armed && rules.isDisarmMobs()) disarm(armed, state, rules);
		if (!wasLegless && rules.crawls() && state.legless()) {
			if (entity instanceof Player player) player.setPose(Pose.SWIMMING, true);
			else if (BodyPose.crawls(state, rules) && entity instanceof Mob mob && rules.mobsLie()) lie(mob);
			else if (BodyPose.crawls(state, rules)) slow(entity, rules);
		}
		if (!wasHeadless && state.headless() && rules.isDecapitationKills() && !entity.isDead() && headless.add(entity.getUniqueId())) {
			double seconds = entity instanceof Player ? rules.getHeadlessPlayerSeconds() : entity instanceof Mob ? rules.getHeadlessSeconds() : 0;
			int ticks = Math.max(1, (int) Math.round(seconds * 20));
			new BukkitRunnable() {
				int left = ticks;

				@Override
				public void run() {
					if (entity.isDead() || !entity.isValid()) {
						headless.remove(entity.getUniqueId());
						cancel();
						return;
					}
					if (entity instanceof Mob mob) {
						mob.setTarget(null);
						if (left % 10 == 0 || !mob.getPathfinder().hasPath()) {
							ThreadLocalRandom random = ThreadLocalRandom.current();
							Location to = mob.getLocation().add(random.nextDouble(-5, 5), 0, random.nextDouble(-5, 5));
							mob.getPathfinder().moveTo(to, 1.3);
						}
					}
					if (--left > 0) return;
					headless.remove(entity.getUniqueId());
					cancel();
					entity.setNoDamageTicks(0);
					// the plugin's own finishing blow: no new wounds from it
					finishing = true;
					try {
						entity.damage(1.0E9, attacker != null && attacker.isValid() ? attacker : null);
					} finally {
						finishing = false;
					}
					if (!entity.isDead()) entity.setHealth(0);
				}
			}.runTaskTimer(plugin, 1, 1);
		}
	}

	/**
	 * Monsters near the killer that are after it: during a kill cam they could kill it unseen.
	 */
	static java.util.List<Mob> threats(Player killer, LivingEntity victim, WoundRules rules) {
		double r = rules.getKillCamThreatRadius();
		java.util.List<Mob> list = new java.util.ArrayList<>();
		for (Entity near : killer.getNearbyEntities(r, r, r)) {
			if (near instanceof Mob mob && mob != victim && !mob.isDead() && killer.equals(mob.getTarget())
					&& mob.getLocation().distanceSquared(killer.getLocation()) <= r * r) list.add(mob);
		}
		return list;
	}

	/**
	 * Holds them still for the kill cam: not aware of anything, as Paper lets a mob be, and aware again after.
	 */
	void freeze(java.util.List<Mob> mobs, WoundRules rules) {
		long ticks = Math.max(1, Math.round(rules.getKillCamFreezeSeconds() * 20));
		for (Mob mob : mobs) {
			if (!mob.isAware() || !frozen.add(mob)) continue;
			mob.setAware(false);
			plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
				if (frozen.remove(mob) && mob.isValid()) mob.setAware(true);
			}, ticks);
		}
	}

	/**
	 * On disable: nobody stays frozen for good.
	 */
	void unfreezeAll() {
		for (Mob mob : lying) if (mob.isValid()) mob.setAware(true);
		lying.clear();
		for (Mob mob : frozen) if (mob.isValid()) mob.setAware(true);
		frozen.clear();
	}

	void clear(LivingEntity entity) {
		if (entity instanceof Mob mob && lying.remove(mob)) mob.setAware(true);
		headless.remove(entity.getUniqueId());
		if (entity instanceof Player player) player.setPose(Pose.STANDING, false);
		AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
		if (speed != null) speed.removeModifier(crawlKey);
	}

	/**
	 * Lies where it fell, AI off; only turns toward the nearest player, a few times a second.
	 */
	private void lie(Mob mob) {
		if (!lying.add(mob)) return;
		mob.setAware(false);
		mob.setTarget(null);
		new BukkitRunnable() {
			@Override
			public void run() {
				if (!lying.contains(mob) || !mob.isValid()) {
					lying.remove(mob);
					cancel();
					return;
				}
				Player nearest = null;
				double best = 16 * 16;
				for (Player player : mob.getWorld().getPlayers()) {
					double d = player.getLocation().distanceSquared(mob.getLocation());
					if (d < best) {
						best = d;
						nearest = player;
					}
				}
				if (nearest == null) return;
				Location eye = mob.getEyeLocation();
				Location look = eye.clone().setDirection(nearest.getEyeLocation().toVector().subtract(eye.toVector()));
				mob.setRotation(look.getYaw(), Math.max(-40, Math.min(40, look.getPitch())));
			}
		}.runTaskTimer(plugin, 1, 4);
	}

	/**
	 * The mob leaves the world (unloaded, removed): its AI comes back before it is saved, its wounds are forgotten.
	 */
	void left(Entity entity) {
		if (entity instanceof Mob mob && lying.remove(mob)) mob.setAware(true);
	}

	private void slow(LivingEntity entity, WoundRules rules) {
		AttributeInstance speed = entity.getAttribute(Attribute.MOVEMENT_SPEED);
		if (speed == null || speed.getModifier(crawlKey) != null) return;
		// transient: wounds of mobs are not saved, a slowness that is would outlive them
		speed.addTransientModifier(new AttributeModifier(crawlKey, rules.getCrawlSpeed() - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1,
				EquipmentSlotGroup.ANY));
	}
}
