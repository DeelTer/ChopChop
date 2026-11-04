package ru.deelter.chopchop.paper;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.Profiles;
import ru.deelter.chopchop.core.rules.WeaponKind;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.server.WoundBook;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.*;

/**
 * The Paper backend: the same wound rules, profiles and protocol as the Fabric server, on Bukkit events.
 * <p>
 * Damage is only seen as an event before it is applied, so a hit is located when the event fires and applied on
 * the next tick if the target really lost health then. Anything that cancels or zeroes the damage later (another
 * plugin, a shield, invulnerability frames) leaves no wound.
 */
public final class ChopPlugin extends JavaPlugin implements Listener, PluginMessageListener {
	private static final double RESEND_RANGE = 160;

	private Profiles profiles;
	private WoundRules rules;
	private WoundBook book;
	private PaperConsequences consequences;
	private NamespacedKey storageKey;
	private final Set<UUID> modded = new HashSet<>();
	private final Map<UUID, Long> lastKick = new HashMap<>();
	/**
	 * the last aim each player sent, for the attack that follows it
	 */
	private final Map<UUID, PendingAim> aims = new HashMap<>();

	private record PendingAim(Protocol.Aim aim, long tick) {
	}

	/**
	 * A dead body as the plugin keeps it for blows at it: its own copy of the wounds, where it fell, until when.
	 */
	private record Corpse(org.bukkit.World world, Vector at, WoundState state, long until) {
	}

	private final Map<Integer, Corpse> corpses = new HashMap<>();
	private final Map<UUID, Long> lastCut = new HashMap<>();
	private int nextCorpseId = -1_000_000;
	private static final int CUT_COOLDOWN = 6;
	private static final double CORPSE_SLACK = 3;

	/**
	 * A blow at a dead body a client says it struck: checked for being near, for the cooldown and for the spot being
	 * on the body, then cut like any body with the weapon in hand. Only for the eye.
	 */
	private void cutCorpse(Player player, Protocol.Aim cut) {
		Corpse corpse = corpses.get(cut.entityId());
		long now = player.getWorld().getFullTime();
		if (corpse == null || corpse.world() != player.getWorld() || corpse.until() < now) return;
		Long last = lastCut.get(player.getUniqueId());
		if (last != null && now - last < CUT_COOLDOWN) return;
		AttributeInstance range = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
		double reach = (range == null ? 3.0 : range.getValue()) + CORPSE_SLACK;
		if (player.getEyeLocation().toVector().distanceSquared(corpse.at()) > reach * reach) return;
		Hit hit = ru.deelter.chopchop.core.hit.AimCheck.corpse(corpse.state(), cut);
		if (hit == null) return;
		lastCut.put(player.getUniqueId(), now);
		player.swingMainHand();
		AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
		float damage = attack == null ? 1 : (float) attack.getValue();
		WeaponKind weapon = classify(player.getInventory().getItemInMainHand());
		for (byte[] message : book.hitCorpse(cut.entityId(), corpse.state(), hit, weapon, damage, rules)) {
			for (Player near : corpse.world().getPlayers()) {
				if (modded.contains(near.getUniqueId()) && near.getLocation().toVector().distanceSquared(corpse.at()) < RESEND_RANGE * RESEND_RANGE) {
					send(near, message);
				}
			}
		}
	}

	/**
	 * The bodies lying in a world within an area, by id: for the API.
	 */
	Map<Integer, Location> corpsesApi(org.bukkit.World world, org.bukkit.util.BoundingBox area) {
		Map<Integer, Location> found = new LinkedHashMap<>();
		long now = world.getFullTime();
		corpses.forEach((id, c) -> {
			if (c.world() == world && c.until() >= now && area.contains(c.at())) found.put(id, c.at().toLocation(world));
		});
		return found;
	}

	/**
	 * Cuts ({@code wound} null) or wounds a body, for the API.
	 */
	boolean changeCorpseApi(int id, String partName, double along, boolean fling, double[] wound) {
		Corpse corpse = corpses.get(id);
		if (corpse == null || corpse.until() < corpse.world().getFullTime()) return false;
		var part = corpse.state().getProfile().part(partName);
		if (part == null) return false;
		int row = ru.deelter.chopchop.core.api.BodyOps.rowAt(part, along);
		List<byte[]> messages = wound == null
				? WoundBook.severCorpse(id, corpse.state(), part.getIndex(), row, fling)
				: WoundBook.woundCorpse(id, corpse.state(), part.getIndex(), row, wound[0], wound[1], (int) wound[2]);
		for (byte[] message : messages) {
			for (Player near : corpse.world().getPlayers()) {
				if (modded.contains(near.getUniqueId()) && near.getLocation().toVector().distanceSquared(corpse.at()) < RESEND_RANGE * RESEND_RANGE) {
					send(near, message);
				}
			}
		}
		return !messages.isEmpty();
	}

	private Protocol.Aim takeAim(Player player, Entity target) {
		PendingAim pending = aims.remove(player.getUniqueId());
		if (pending == null || pending.aim().entityId() != target.getEntityId()) return null;
		return player.getWorld().getFullTime() - pending.tick() <= 2 ? pending.aim() : null;
	}
	private final List<Pending> pending = new ArrayList<>();
	private boolean flushScheduled;

	/**
	 * @param blast spots a blast tears at, null for any other hit
	 * @param from  where the blast went off
	 */
	private record Pending(LivingEntity target, double healthBefore, Hit hit, WeaponKind weapon, Entity attacker, List<Hit> blast,
						   Location from, double dealt, int sharpness) {
	}

	private static ChopPlugin instance;
	/**
	 * which damage the plugin turns into wounds on its own, see {@link ru.deelter.chopchop.paper.api.ChopChopApi}
	 */
	private java.util.function.BiPredicate<LivingEntity, EntityDamageEvent> automatic = (target, event) -> true;

	static ChopPlugin instance() {
		return instance;
	}

	@Override
	public void onEnable() {
		instance = this;
		saveDefaultConfig();
		profiles = Profiles.loadBuiltin();
		storageKey = new NamespacedKey(this, "wounds");
		reload();
		consequences = new PaperConsequences(this);
		// a player without a leg keeps limping: its Slowness comes back if milk or a command takes it
		getServer().getScheduler().runTaskTimer(this, () -> {
			for (Player player : getServer().getOnlinePlayers()) consequences.limp(player, book.get(player.getUniqueId()), rules);
		}, 40, 40);
		book = new WoundBook(profiles, new Random());
		getServer().getMessenger().registerIncomingPluginChannel(this, Protocol.CHANNEL, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);
		getServer().getPluginManager().registerEvents(this, this);
	}

	@Override
	public void onDisable() {
		if (consequences != null) consequences.unfreezeAll();
		if (rules.isPersistPlayers()) getServer().getOnlinePlayers().forEach(this::store);
	}

	private void reload() {
		reloadConfig();
		rules = PaperConfig.read(getConfig());
		profiles = Profiles.loadBuiltin();
		var section = getConfig().getConfigurationSection("entity-profiles");
		if (section != null) {
			for (String key : section.getKeys(false)) profiles.assign(key, section.getString(key, ""));
		}
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			reload();
			book = new WoundBook(profiles, new Random());
			sender.sendMessage("ChopChop reloaded, wounds cleared");
			return true;
		}
		if (args.length >= 1 && args[0].equalsIgnoreCase("heal")) {
			Player target = args.length > 1 ? getServer().getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
			if (target == null) {
				sender.sendMessage("No such player");
				return true;
			}
			heal(target);
			sender.sendMessage("Healed " + target.getName());
			return true;
		}
		if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
			sender.sendMessage("ChopChop: " + book.size() + " wounded, " + modded.size() + " players with the mod");
			return true;
		}
		return false;
	}

	@Override
	public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
		// a player with no arms digs with a leg: the others see it kick, at most a few times a second
		if (Protocol.CHANNEL.equals(channel) && message.length > 0 && message[0] == Protocol.C_LIMB) {
			int leg = Protocol.readLimb(message);
			long now = player.getWorld().getFullTime();
			Long last = lastKick.get(player.getUniqueId());
			if (leg >= 0 && modded.contains(player.getUniqueId()) && (last == null || now - last >= 4)) {
				lastKick.put(player.getUniqueId(), now);
				byte[] kick = Protocol.limb(player.getEntityId(), leg);
				for (Player other : player.getTrackedBy()) if (modded.contains(other.getUniqueId())) send(other, kick);
			}
			return;
		}
		if (Protocol.CHANNEL.equals(channel) && message.length > 0 && message[0] == Protocol.C_CUT) {
			Protocol.Aim cut = Protocol.readCut(message);
			if (cut != null && modded.contains(player.getUniqueId())) cutCorpse(player, cut);
			return;
		}
		if (Protocol.CHANNEL.equals(channel) && message.length > 0 && message[0] == Protocol.C_AIM) {
			Protocol.Aim aim = Protocol.readAim(message);
			if (aim != null && modded.contains(player.getUniqueId())) aims.put(player.getUniqueId(), new PendingAim(aim, player.getWorld().getFullTime()));
			return;
		}
		if (Protocol.CHANNEL.equals(channel) && Protocol.readServerbound(message) != Protocol.VERSION) {
			getLogger().info(player.getName() + " has a ChopChop of another protocol version");
			return;
		}
		if (!Protocol.CHANNEL.equals(channel) || Protocol.readServerbound(message) != Protocol.VERSION) return;
		if (!modded.add(player.getUniqueId())) return;
		getLogger().info(player.getName() + " has the ChopChop mod");
		// the client names the channels it listens on in its own time, often after its hello: until then
		// Paper drops everything sent to it, so the answer waits for onChannel
		if (player.getListeningPluginChannels().contains(Protocol.CHANNEL)) welcome(player);
	}

	@EventHandler
	public void onChannel(org.bukkit.event.player.PlayerRegisterChannelEvent event) {
		if (Protocol.CHANNEL.equals(event.getChannel()) && modded.contains(event.getPlayer().getUniqueId())) welcome(event.getPlayer());
	}

	private void welcome(Player player) {
		send(player, Protocol.hello(Protocol.flags(rules)));
		WoundState own = book.get(player.getUniqueId());
		if (own != null) send(player, Protocol.state(player.getEntityId(), own));
		for (UUID id : List.copyOf(book.ids())) {
			Entity entity = getServer().getEntity(id);
			if (entity == null || entity == player || entity.getWorld() != player.getWorld()
					|| entity.getLocation().distanceSquared(player.getLocation()) > RESEND_RANGE * RESEND_RANGE) continue;
			send(player, Protocol.state(entity.getEntityId(), book.get(id)));
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDamage(EntityDamageEvent event) {
		if (!rules.isEnabled() || !(event.getEntity() instanceof LivingEntity target) || event.getFinalDamage() <= 0) return;
		if (target instanceof Player ? !rules.isPlayers() : !rules.isMobs()) return;
		BodyProfile profile = profiles.forEntity(target.getType().getKey().toString());
		if (profile == null) return;
		if (consequences.finishing || !automaticAllows(target, event)) return;
		WoundState state = book.getOrBlank(target.getUniqueId(), profile);
		EntityDamageEvent.DamageCause cause = event.getCause();
		if (rules.isExplosions() && (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) {
			// where it went off: the exploding entity, or for a bed or an anchor the place of the block
			Location from = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager().getLocation()
					: event instanceof org.bukkit.event.entity.EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null
					? byBlock.getDamager().getLocation().add(0.5, 0.5, 0.5) : target.getLocation().add(0, 0.5, 0);
			List<Hit> hits = HitLocator.blast(state, pose(target).crawling(state, rules), from.getX(), from.getY(), from.getZ(),
					target.getWidth(), target.getHeight(), rules.getExplosionWounds(), java.util.concurrent.ThreadLocalRandom.current());
			if (hits.isEmpty()) return;
			pending.add(new Pending(target, health(target), hits.getFirst(), WeaponKind.OTHER, attacker(event), hits, from, event.getFinalDamage(), 0));
		} else {
			Located located = locate(target, event, state);
			if (located == null) return;
			pending.add(new Pending(target, health(target), located.hit, located.weapon, attacker(event), null, null, event.getFinalDamage(),
					located.sharpness));
		}
		if (!flushScheduled) {
			flushScheduled = true;
			getServer().getScheduler().runTask(this, this::flush);
		}
	}

	private void flush() {
		flushScheduled = false;
		List<Pending> list = List.copyOf(pending);
		pending.clear();
		for (Pending p : list) {
			LivingEntity target = p.target;
			float lost = (float) (p.healthBefore - (target.isDead() ? 0 : health(target)));
			if (lost <= 0 || !target.isValid() && !target.isDead()) continue;
			BodyProfile profile = profiles.forEntity(target.getType().getKey().toString());
			if (profile == null) continue;
			WoundState state = book.getOrBlank(target.getUniqueId(), profile);
			if (p.hit.part() >= state.partCount()) continue;
			boolean wasLegless = state.legless(), wasHeadless = state.headless();
			List<Mob> threats = List.of();
			int attacker = p.attacker == null ? -1 : p.attacker.getEntityId();
			if (p.blast == null && p.attacker instanceof Player killer && profile.part(p.hit.part()).isHead()) {
				// a kill cam only where it does not leave the player helpless
				threats = PaperConsequences.threats(killer, target, rules);
				if (!threats.isEmpty() && "skip".equalsIgnoreCase(rules.getKillCamThreats())) attacker = -1;
			}
			List<byte[]> messages = p.blast != null
					? book.blast(target.getUniqueId(), target.getEntityId(), state, p.blast, lost, blastHead(target, state, p.from), rules)
					: book.hit(target.getUniqueId(), target.getEntityId(), state, p.hit, p.weapon,
					target.isDead() ? (float) Math.max(lost, Math.min(40, p.dealt)) : lost, target.isDead(), p.sharpness, rules, attacker);
			for (byte[] message : messages) broadcast(target, message);
			if (!wasHeadless && state.headless() && attacker >= 0 && p.blast == null && "freeze".equalsIgnoreCase(rules.getKillCamThreats())) {
				consequences.freeze(threats, rules);
			}
			consequences.apply(target, p.attacker, state, wasLegless, wasHeadless, rules);
			if (target instanceof Player player && rules.isPersistPlayers()) store(player);
		}
	}

	private boolean automaticAllows(LivingEntity target, EntityDamageEvent event) {
		try {
			return automatic.test(target, event);
		} catch (RuntimeException e) {
			getLogger().log(java.util.logging.Level.WARNING, "The ChopChop damage filter of another plugin threw", e);
			return true;
		}
	}

	/**
	 * A mob dies: the clients with the mod keep it on the ground as a body, falling away from the last blow.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onDeath(org.bukkit.event.entity.EntityDeathEvent event) {
		LivingEntity target = event.getEntity();
		if (!rules.isEnabled() || rules.getCorpseSeconds() <= 0 || target instanceof Player && !rules.isPlayerCorpses()) return;
		BodyProfile bodyProfile = profiles.forEntity(target.getType().getKey().toString());
		if (bodyProfile == null || !bodyProfile.isCorpse()) return;
		Vector push = new Vector();
		if (target.getLastDamageCause() instanceof EntityDamageByEntityEvent by) {
			push = target.getLocation().toVector().subtract(by.getDamager().getLocation().toVector());
		}
		push.setY(0);
		if (push.lengthSquared() < 1e-6) push = target.getVelocity().setY(0);
		if (push.lengthSquared() < 1e-6) push = target.getLocation().getDirection().multiply(-1).setY(0);
		if (push.lengthSquared() < 1e-6) push = new Vector(0, 0, 1);
		push.normalize();
		double damage = target.getLastDamageCause() == null ? 4 : target.getLastDamageCause().getFinalDamage();
		float strength = (float) Math.min(1.5, 0.25 + Math.min(40, damage) / 20.0);
		int ticks = (int) Math.round(rules.getCorpseSeconds() * 20);
		// a mob's body keeps its entity id on the clients; a player comes back under its id, its body gets one of its own
		int corpseId = target instanceof Player ? nextCorpseId-- : target.getEntityId();
		BodyProfile profile = profiles.forEntity(target.getType().getKey().toString());
		// at once, so a blow at the body right away is not lost; the killing blow itself is applied a tick later
		// (flush): the body takes it then, unless it was cut already
		long until = target.getWorld().getFullTime() + ticks * 3L;
		Corpse first = new Corpse(target.getWorld(), target.getLocation().toVector(), book.copyOf(book.get(target.getUniqueId()), profile), until);
		corpses.put(corpseId, first);
		int firstRevision = first.state().getRevision();
		getServer().getScheduler().runTaskLater(this, () -> {
			if (corpses.get(corpseId) == first && first.state().getRevision() == firstRevision) {
				corpses.put(corpseId, new Corpse(first.world(), first.at(), book.copyOf(book.get(target.getUniqueId()), profile), until));
			}
		}, 2);
		byte[] message = Protocol.corpse(target.getEntityId(), (float) push.getX(), (float) push.getZ(), strength,
				java.util.concurrent.ThreadLocalRandom.current().nextInt(), ticks, corpseId);
		broadcast(target, message);
		if (corpses.size() > 256) corpses.values().removeIf(c -> c.until() < c.world().getFullTime());
	}

	/**
	 * No arms, no blows; no hand, no bow and nothing picked up.
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onMobAttack(EntityDamageByEntityEvent event) {
		if (event.getDamager() instanceof Mob mob && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
				&& consequences.armless(mob, book.get(mob.getUniqueId()), rules)) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onShoot(org.bukkit.event.entity.EntityShootBowEvent event) {
		if (event.getEntity() instanceof Mob mob && consequences.mainHandless(mob, book.get(mob.getUniqueId()), rules)) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
		if (event.getEntity() instanceof Mob mob && consequences.mainHandless(mob, book.get(mob.getUniqueId()), rules)) event.setCancelled(true);
	}

	// for the public API, see ru.deelter.chopchop.paper.api.ChopChopApi

	WoundBook book() {
		return book;
	}

	Profiles profiles() {
		return profiles;
	}

	WoundRules rules() {
		return rules;
	}

	void setAutomatic(java.util.function.BiPredicate<LivingEntity, EntityDamageEvent> filter) {
		automatic = filter == null ? (target, event) -> true : filter;
	}

	BodyPose poseOf(LivingEntity entity, WoundState state) {
		return pose(entity).crawling(state, rules);
	}

	void broadcastApi(Entity entity, byte[] message) {
		broadcast(entity, message);
	}

	void consequencesApi(LivingEntity target, Entity attacker, WoundState state, boolean wasLegless, boolean wasHeadless) {
		consequences.apply(target, attacker, state, wasLegless, wasHeadless, rules);
		if (target instanceof Player player && rules.isPersistPlayers()) store(player);
	}

	public void healApi(LivingEntity entity) {
		consequences.clear(entity);
		if (book.clear(entity.getUniqueId())) broadcast(entity, Protocol.clear(entity.getEntityId()));
		if (entity instanceof Player player) player.getPersistentDataContainer().remove(storageKey);
	}

	/**
	 * Whoever gets the kill if this hit takes the head: the shooter of a projectile, the attacker of a swing.
	 */
	private static Entity attacker(EntityDamageEvent event) {
		if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
		Entity damager = byEntity.getDamager();
		if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) return shooter;
		return damager;
	}

	/**
	 * A player the blast killed loses the head, thrown away from where it went off.
	 */
	private float[] blastHead(LivingEntity target, WoundState state, Location from) {
		var head = state.getProfile().head();
		if (!(target instanceof Player) || !target.isDead() || !rules.isExplosionDeathTakesHead() || head == null || from == null) return null;
		return HitLocator.facing(state.getProfile(), head, pose(target).crawling(state, rules), from.getX(), from.getY(), from.getZ());
	}

	private static double health(LivingEntity entity) {
		return entity.getHealth() + entity.getAbsorptionAmount();
	}

	private record Located(Hit hit, WeaponKind weapon, int sharpness) {
		Located(Hit hit, WeaponKind weapon) {
			this(hit, weapon, 0);
		}

	}

	private Located locate(LivingEntity target, EntityDamageEvent event, WoundState state) {
		// a mob that crawls lies on the ground: hits are found on the body as the clients draw it
		BodyPose pose = pose(target).crawling(state, rules);
		Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
		if (damager instanceof AbstractArrow arrow) {
			Vector velocity = arrow.getVelocity();
			if (velocity.lengthSquared() < 1e-6) velocity = target.getBoundingBox().getCenter().subtract(arrow.getLocation().toVector());
			Vector dir = velocity.clone().normalize();
			Vector origin = arrow.getLocation().toVector().subtract(dir.clone().multiply(1.5));
			double length = velocity.length() + 4;
			Hit hit = HitLocator.cast(state, pose, origin.getX(), origin.getY(), origin.getZ(), dir.getX(), dir.getY(), dir.getZ(), length);
			if (hit == null) {
				hit = HitLocator.castOrNearest(state, pose, origin.getX(), origin.getY(), origin.getZ(), dir.getX(), dir.getY(), dir.getZ(), length);
			}
			return hit == null ? null : new Located(hit, arrow instanceof Trident ? WeaponKind.TRIDENT : WeaponKind.ARROW);
		}
		if (damager instanceof LivingEntity && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
				|| damager instanceof LivingEntity && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
			LivingEntity living = (LivingEntity) damager;
			Location eye = living.getEyeLocation();
			Vector dir;
			double reach;
			if (living instanceof Player) {
				dir = eye.getDirection();
				// as the Fabric server: the reach of the player and a little more
				AttributeInstance range = living.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
				reach = (range == null ? 3.0 : range.getValue()) + 1.5;
			} else {
				Vector center = target.getBoundingBox().getCenter();
				ItemStack held = living.getEquipment() == null ? null : living.getEquipment().getItemInMainHand();
				if (classify(held).edged()) {
					// a blade goes where the mob looks (zombies look you in the eyes): heads fly
					Vector toCenter = center.clone().subtract(eye.toVector()).normalize();
					dir = eye.getDirection().dot(toCenter) > 0.85 ? eye.getDirection() : toCenter;
				} else {
					// bare hands and teeth tear at the whole body
					double[] at = ru.deelter.chopchop.core.hit.MobAim.spread(java.util.concurrent.ThreadLocalRandom.current(),
							target.getWidth(), target.getHeight());
					dir = target.getLocation().toVector().add(new Vector(at[0], at[1], at[2])).subtract(eye.toVector()).normalize();
				}
				reach = eye.toVector().distance(center) + 2;
			}
			Hit hit = null;
			// a client with the mod says where it saw the blow land on the animated body; checked, never trusted blindly
			if (living instanceof Player player) {
				Vector unit = dir.clone().normalize();
				hit = ru.deelter.chopchop.core.hit.AimCheck.accept(state, pose, takeAim(player, target), eye.getX(), eye.getY(), eye.getZ(),
						unit.getX(), unit.getY(), unit.getZ(), reach);
			}
			if (hit == null) hit = HitLocator.castOrNearest(state, pose, eye.getX(), eye.getY(), eye.getZ(), dir.getX(), dir.getY(), dir.getZ(), reach);
			ItemStack weapon = living.getEquipment() == null ? null : living.getEquipment().getItemInMainHand();
			return hit == null ? null : new Located(hit, classify(weapon),
					weapon == null ? 0 : weapon.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS));
		}
		if (!"coarse".equalsIgnoreCase(rules.getOtherSources())) return null;
		Vector center = target.getBoundingBox().getCenter();
		Vector from;
		if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
			from = target.getLocation().toVector().add(new Vector(0, -1, 0));
			center = target.getLocation().toVector().add(new Vector(0, 0.5, 0));
		} else if (damager != null) {
			from = damager.getLocation().toVector();
		} else {
			return null;
		}
		Vector dir = center.clone().subtract(from);
		if (dir.lengthSquared() < 1e-6) return null;
		double length = dir.length() + 3;
		dir.normalize();
		Hit hit = HitLocator.castOrNearest(state, pose, from.getX(), from.getY(), from.getZ(), dir.getX(), dir.getY(), dir.getZ(), length);
		return hit == null ? null : new Located(hit, WeaponKind.OTHER);
	}

	private static BodyPose pose(LivingEntity entity) {
		Location location = entity.getLocation();
		float scale = 1;
		AttributeInstance attribute = entity.getAttribute(Attribute.SCALE);
		if (attribute != null) scale = (float) attribute.getValue();
		if (entity instanceof Ageable ageable && !ageable.isAdult()) scale *= 0.5F;
		// as the client turns a swimming body: with the look in water, flat when crawling on land
		boolean flat = entity.isSwimming() || entity.isGliding() || entity.getPose() == org.bukkit.entity.Pose.SWIMMING;
		float pitch = !flat ? 0 : entity.isInWater() || entity.isGliding() ? -90 - location.getPitch() : -90;
		return new BodyPose(location.getX(), location.getY(), location.getZ(), entity.getBodyYaw(), scale, pitch);
	}

	static WeaponKind classify(ItemStack stack) {
		if (stack == null) return WeaponKind.BLUNT;
		if (Tag.ITEMS_SWORDS.isTagged(stack.getType())) return WeaponKind.SWORD;
		if (Tag.ITEMS_AXES.isTagged(stack.getType())) return WeaponKind.AXE;
		String name = stack.getType().name();
		if (name.equals("TRIDENT") || name.endsWith("_SPEAR")) return WeaponKind.TRIDENT;
		return WeaponKind.BLUNT;
	}

	@EventHandler
	public void onTrack(PlayerTrackEntityEvent event) {
		if (!modded.contains(event.getPlayer().getUniqueId())) return;
		WoundState state = book.get(event.getEntity().getUniqueId());
		if (state != null) send(event.getPlayer(), Protocol.state(event.getEntity().getEntityId(), state));
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		// the same living player keeps the wounds the server has for it, a reconnect heals nothing
		if (book.get(player.getUniqueId()) == null && rules.isPersistPlayers()) {
			String data = player.getPersistentDataContainer().get(storageKey, PersistentDataType.STRING);
			if (data != null) book.put(player.getUniqueId(), book.decode(data));
		}
		// a player who left crawling comes back crawling
		WoundState own = book.get(player.getUniqueId());
		if (own != null && rules.crawls() && own.legless()) player.setPose(org.bukkit.entity.Pose.SWIMMING, true);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		modded.remove(event.getPlayer().getUniqueId());
		aims.remove(event.getPlayer().getUniqueId());
		lastCut.remove(event.getPlayer().getUniqueId());
		lastKick.remove(event.getPlayer().getUniqueId());
		if (rules.isPersistPlayers()) store(event.getPlayer());
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) {
			getServer().getScheduler().runTask(this, () -> resendSelf(player));
			return;
		}
		consequences.clear(player);
		book.clear(player.getUniqueId());
		player.getPersistentDataContainer().remove(storageKey);
		getServer().getScheduler().runTask(this, () -> broadcast(player, Protocol.clear(player.getEntityId())));
	}

	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		resendSelf(event.getPlayer());
	}

	/**
	 * Before a chunk of entities is saved: a mob lying without legs gets its AI back, its wounds do not last.
	 */
	@EventHandler
	public void onUnload(org.bukkit.event.world.EntitiesUnloadEvent event) {
		for (Entity entity : event.getEntities()) consequences.left(entity);
	}

	@EventHandler
	public void onRemove(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
		consequences.left(event.getEntity());
		if (!(event.getEntity() instanceof Player)) book.forget(event.getEntity().getUniqueId());
	}

	private void heal(Player player) {
		consequences.clear(player);
		if (book.clear(player.getUniqueId())) broadcast(player, Protocol.clear(player.getEntityId()));
		player.getPersistentDataContainer().remove(storageKey);
	}

	private void resendSelf(Player player) {
		if (!player.isOnline() || !modded.contains(player.getUniqueId())) return;
		WoundState state = book.get(player.getUniqueId());
		send(player, state == null ? Protocol.clear(player.getEntityId()) : Protocol.state(player.getEntityId(), state));
	}

	private void store(Player player) {
		WoundState state = book.get(player.getUniqueId());
		if (state == null) player.getPersistentDataContainer().remove(storageKey);
		else player.getPersistentDataContainer().set(storageKey, PersistentDataType.STRING, WoundBook.encode(state));
	}

	private void broadcast(Entity entity, byte[] message) {
		for (Player player : entity.getTrackedBy()) {
			if (modded.contains(player.getUniqueId())) send(player, message);
		}
		if (entity instanceof Player self && modded.contains(self.getUniqueId())) send(self, message);
	}

	private void send(Player player, byte[] message) {
		player.sendPluginMessage(this, Protocol.CHANNEL, message);
	}
}
