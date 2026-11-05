package ru.deelter.chopchop.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.Crawling;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.hit.BodyPose;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.Profiles;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.server.WoundBook;
import ru.deelter.chopchop.core.wound.WoundState;
import ru.deelter.chopchop.net.ChopPayload;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The server side of the mod: decides wounds from damage the game really dealt and tells the clients that see the
 * entity. The same on a dedicated server and on the integrated one.
 */
public final class ChopServer {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final double RESEND_RANGE = 160;
	/**
	 * the most damage of one blow that counts towards severing
	 */
	private static final float MAX_BLOW = 40;

	private static ServerConfig config;
	private static WoundBook book;
	private static MinecraftServer running;
	private static Profiles profiles;
	/**
	 * players whose client said hello with a version this server speaks
	 */
	private static final Set<UUID> MODDED = new HashSet<>();
	/**
	 * ids in the book that belong to players, for keeping them over a restart
	 */
	private static final Set<UUID> PLAYERS = new HashSet<>();
	private static final java.util.random.RandomGenerator RANDOM = new Random();
	private static final Map<UUID, Long> LAST_KICK = new HashMap<>();
	/**
	 * the last aim each player sent, for the attack that follows it in the same tick
	 */
	private static final Map<UUID, PendingAim> AIMS = new HashMap<>();

	record PendingAim(Protocol.Aim aim, long tick) {
	}

	/**
	 * A dead body as the server keeps it for blows at it: its own copy of the wounds, where it fell, until when.
	 */
	record Corpse(ServerLevel level, Vec3 at, WoundState state, long until) {
	}

	/**
	 * dead bodies by the id they go by on the clients
	 */
	private static final Map<Integer, Corpse> CORPSES = new HashMap<>();
	/**
	 * ids for the bodies of players, far from any entity id
	 */
	private static int nextCorpseId = -1_000_000;
	private static final Map<UUID, Long> LAST_CUT = new HashMap<>();
	/**
	 * ticks between two blows at a body, about a sword's cooldown
	 */
	private static final int CUT_COOLDOWN = 6;
	/**
	 * how far a body may have slid or been thrown from where it fell, blocks
	 */
	private static final double CORPSE_SLACK = 3;

	/**
	 * A blow at a dead body a client says it struck. Only for the eye: checked for being near, for the cooldown and
	 * for the spot being on the body, then cut like any body with the weapon in hand.
	 */
	private static void cutCorpse(ServerPlayer player, Protocol.Aim cut) {
		Corpse corpse = CORPSES.get(cut.entityId());
		WoundRules rules = rules();
		if (corpse == null || rules == null || corpse.level() != player.level() || corpse.until() < player.level().getGameTime()) return;
		long now = player.level().getGameTime();
		Long last = LAST_CUT.get(player.getUUID());
		if (last != null && now - last < CUT_COOLDOWN) return;
		double reach = player.entityInteractionRange() + CORPSE_SLACK;
		if (player.getEyePosition().distanceToSqr(corpse.at()) > reach * reach) return;
		Hit hit = ru.deelter.chopchop.core.hit.AimCheck.corpse(corpse.state(), cut);
		if (hit == null) return;
		LAST_CUT.put(player.getUUID(), now);
		// the others see the swing; the player swung already on its own client
		ru.deelter.chopchop.version.Swings.swingForOthers(player);
		float damage = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
		var weapon = FabricHits.classify(player.getMainHandItem());
		for (byte[] message : book.hitCorpse(cut.entityId(), corpse.state(), hit, weapon, damage, rules)) {
			for (ServerPlayer near : corpse.level().players()) {
				if (MODDED.contains(near.getUUID()) && near.distanceToSqr(corpse.at()) < RESEND_RANGE * RESEND_RANGE) send(near, message);
			}
		}
	}

	/**
	 * The bodies lying in a world within an area, by id: for the API.
	 */
	public static Map<Integer, Vec3> corpses(ServerLevel level, net.minecraft.world.phys.AABB area) {
		Map<Integer, Vec3> found = new LinkedHashMap<>();
		long now = level.getGameTime();
		CORPSES.forEach((id, c) -> {
			if (c.level() == level && c.until() >= now && area.contains(c.at())) found.put(id, c.at());
		});
		return found;
	}

	/**
	 * Cuts or wounds a body, for the API: {@code wound} null cuts it off at {@code along}, else carves.
	 *
	 * @param wound half length, radius, depth of a wound
	 * @return whether anything changed
	 */
	public static boolean changeCorpse(int id, String partName, double along, boolean fling, double[] wound) {
		Corpse corpse = CORPSES.get(id);
		if (corpse == null || corpse.until() < corpse.level().getGameTime()) return false;
		var part = corpse.state().getProfile().part(partName);
		if (part == null) return false;
		int row = ru.deelter.chopchop.core.api.BodyOps.rowAt(part, along);
		List<byte[]> messages = wound == null
				? WoundBook.severCorpse(id, corpse.state(), part.getIndex(), row, fling)
				: WoundBook.woundCorpse(id, corpse.state(), part.getIndex(), row, wound[0], wound[1], (int) wound[2]);
		for (byte[] message : messages) {
			for (ServerPlayer near : corpse.level().players()) {
				if (MODDED.contains(near.getUUID()) && near.distanceToSqr(corpse.at()) < RESEND_RANGE * RESEND_RANGE) send(near, message);
			}
		}
		return !messages.isEmpty();
	}

	/**
	 * Forgets bodies whose time on every client is up.
	 */
	/**
	 * Bodies whose wounds are taken again a moment after the death: what the killing blow cut off after it killed (a
	 * combat mod hurts first, then severs) belongs to the body too, unless the body was cut in between.
	 */
	private record Recopy(int corpseId, Corpse first, int revision, java.util.UUID entity, BodyProfile profile, int due) {
	}

	private static final List<Recopy> RECOPIES = new ArrayList<>();

	private static void tickCorpses(MinecraftServer server) {
		if (!RECOPIES.isEmpty()) {
			for (java.util.Iterator<Recopy> it = RECOPIES.iterator(); it.hasNext(); ) {
				Recopy r = it.next();
				if (server.getTickCount() < r.due()) continue;
				it.remove();
				WoundState now = book == null ? null : book.get(r.entity());
				if (now != null && CORPSES.get(r.corpseId()) == r.first() && r.first().state().getRevision() == r.revision()) {
					CORPSES.put(r.corpseId(), new Corpse(r.first().level(), r.first().at(), book.copyOf(now, r.profile()), r.first().until()));
				}
			}
		}
		if (CORPSES.isEmpty() || server.getTickCount() % 20 != 0) return;
		CORPSES.values().removeIf(c -> c.until() < c.level().getGameTime());
	}

	/**
	 * The aim a player sent for this attack on this target, taken once; null if none or stale.
	 */
	static Protocol.Aim takeAim(ServerPlayer player, Entity target) {
		PendingAim pending = AIMS.remove(player.getUUID());
		if (pending == null || pending.aim().entityId() != target.getId()) return null;
		return player.level().getGameTime() - pending.tick() <= 2 ? pending.aim() : null;
	}

	private ChopServer() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			running = server;
			config = ServerConfig.load();
			// a copy of its own: the assignments of one world must not stay for the next world of the same game
			profiles = Profiles.loadBuiltin();
			config.getEntityProfiles().forEach(profiles::assign);
			book = new WoundBook(profiles, new Random());
			MODDED.clear();
			PLAYERS.clear();
			AIMS.clear();
			CORPSES.clear();
			LAST_CUT.clear();
			Consequences.reset();
			if (config.getRules().isPersistPlayers()) loadPlayers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (config != null && config.getRules().isPersistPlayers()) savePlayers(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			book = null;
			Consequences.reset();
			MODDED.clear();
		});

		ServerPlayNetworking.registerGlobalReceiver(ChopPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			// a player with no arms digs with a leg: the others see it kick, at most a few times a second
			int leg = Protocol.readLimb(payload.data());
			if (leg >= 0) {
				long now = player.level().getGameTime();
				Long last = LAST_KICK.get(player.getUUID());
				if (MODDED.contains(player.getUUID()) && (last == null || now - last >= 4)) {
					LAST_KICK.put(player.getUUID(), now);
					byte[] kick = Protocol.limb(player.getId(), leg);
					for (ServerPlayer other : PlayerLookup.tracking(player)) if (MODDED.contains(other.getUUID())) send(other, kick);
				}
				return;
			}
			Protocol.Aim cut = Protocol.readCut(payload.data());
			if (cut != null) {
				if (MODDED.contains(player.getUUID())) cutCorpse(player, cut);
				return;
			}
			Protocol.Aim aim = Protocol.readAim(payload.data());
			if (aim != null) {
				if (MODDED.contains(player.getUUID())) AIMS.put(player.getUUID(), new PendingAim(aim, player.level().getGameTime()));
				return;
			}
			int version = Protocol.readServerbound(payload.data());
			if (version != Protocol.VERSION) return;
			MODDED.add(player.getUUID());
			send(player, Protocol.hello(Protocol.flags(config.getRules())));
			sendKnownAround(player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			MODDED.remove(handler.getPlayer().getUUID());
			AIMS.remove(handler.getPlayer().getUUID());
			LAST_CUT.remove(handler.getPlayer().getUUID());
			LAST_KICK.remove(handler.getPlayer().getUUID());
		});

		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (book == null || !MODDED.contains(player.getUUID())) return;
			WoundState state = book.get(entity.getUUID());
			if (state != null) send(player, Protocol.state(entity.getId(), state));
		});
		// mobs that leave the loaded world are whole when they come back; players keep their wounds until respawn
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (book != null && !(entity instanceof Player) && entity.getRemovalReason() != null) book.forget(entity.getUUID());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (book == null) return;
			if (!alive) {
				book.clear(newPlayer.getUUID());
				PLAYERS.remove(newPlayer.getUUID());
				broadcast(newPlayer, Protocol.clear(newPlayer.getId()));
			} else {
				resendSelf(newPlayer);
			}
		});
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> resendSelf(player));
		ServerTickEvents.END_SERVER_TICK.register(Consequences::tick);
		ServerTickEvents.END_SERVER_TICK.register(ChopServer::tickCorpses);
		// players crawl in the vanilla pose, their profile has no crawl of its own
		Crawling.server = player -> {
			WoundState state = book == null ? null : book.get(player.getUUID());
			WoundRules rules = rules();
			return state != null && rules != null && rules.crawls() && state.legless();
		};
	}

	/**
	 * A mob without legs that lies still, see {@code MobAiMixin}.
	 */
	public static boolean lies(Mob mob) {
		if (book == null || config == null || !config.getRules().mobsLie()) return false;
		WoundState state = book.get(mob.getUUID());
		return state != null && BodyPose.crawls(state, config.getRules());
	}

	/**
	 * A mob with no arm left, see {@code MobArmsMixin}.
	 */
	public static boolean armless(Mob mob) {
		return Consequences.noHands(mob);
	}

	/**
	 * A mob without the hand it holds things in.
	 */
	public static boolean mainHandless(Mob mob) {
		return Consequences.noMainHand(mob);
	}

	/**
	 * What the server knows of an entity's wounds, null when it is whole.
	 */
	/**
	 * By UUID: what the server knows of the wounds of an entity, null when it is whole.
	 */
	public static WoundState wounds(UUID id) {
		return book == null ? null : book.get(id);
	}

	/**
	 * The server that runs now, null between worlds.
	 */
	public static MinecraftServer server() {
		return book == null ? null : running;
	}

	public static WoundState wounds(Entity entity) {
		return book == null ? null : book.get(entity.getUUID());
	}

	public static WoundRules rules() {
		return config == null ? null : config.getRules();
	}

	/**
	 * Called once the game dealt damage that took health (see the mixin on {@code LivingEntity.hurtServer}).
	 *
	 * @param lost health and absorption the hit took
	 */
	public static void onDamaged(LivingEntity target, DamageSource source, float lost) {
		onDamaged(target, source, lost, lost);
	}

	/**
	 * @param dealt the damage of the blow before armor: the killing blow of a weak target is judged by it, not by
	 *              the little health that was left
	 */
	public static void onDamaged(LivingEntity target, DamageSource source, float lost, float dealt) {
		if (book == null || config == null) return;
		if (Consequences.finishing || ChopApiHooks.skipsAutomatic(target, source)) return;
		// a kill command or the like deals any amount: judge a blow by what a blow can be
		dealt = Math.min(dealt, MAX_BLOW);
		WoundRules rules = config.getRules();
		if (!rules.isEnabled() || lost <= 0) return;
		boolean player = target instanceof Player;
		if (player ? !rules.isPlayers() : !rules.isMobs()) return;
		BodyProfile profile = profiles.forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
		if (profile == null) return;

		WoundState state = book.getOrBlank(target.getUUID(), profile);
		boolean wasLegless = state.legless(), wasHeadless = state.headless();
		if (rules.isExplosions() && source.is(DamageTypeTags.IS_EXPLOSION)) {
			blast(target, source, state, lost, rules, wasLegless, wasHeadless, player);
			return;
		}
		FabricHits.Resolved resolved = FabricHits.resolve(target, source, state, rules);
		if (resolved == null) return;
		Hit hit = resolved.hit();
		List<Mob> threats = List.of();
		int attacker = source.getEntity() == null ? -1 : source.getEntity().getId();
		if (source.getEntity() instanceof ServerPlayer killer && profile.part(hit.part()).isHead()) {
			// a kill cam only where it does not leave the player helpless
			threats = Consequences.threats(killer, target, rules);
			if (!threats.isEmpty() && "skip".equalsIgnoreCase(rules.getKillCamThreats())) attacker = -1;
		}
		boolean lethal = target.isDeadOrDying();
		float damage = lethal ? Math.max(lost, dealt) : lost;
		List<byte[]> messages = book.hit(target.getUUID(), target.getId(), state, hit, resolved.weapon(), damage, lethal,
				resolved.sharpness(), rules, attacker);
		if (player && book.get(target.getUUID()) != null) PLAYERS.add(target.getUUID());
		for (byte[] message : messages) broadcast(target, message);
		Consequences.apply(target, source, state, wasLegless, wasHeadless, rules);
		if (!wasHeadless && state.headless() && attacker >= 0 && "freeze".equalsIgnoreCase(rules.getKillCamThreats())) {
			Consequences.freeze(threats, rules);
		}
	}

	/**
	 * A mob or a player died: it stays on the ground as a body for the clients with the mod, falling away from the
	 * last blow. The clients keep a mob in their world, and draw a copy of a player (it respawns under the same id).
	 * The server forgets nothing early: the wounds go when the entity is removed, as before.
	 */
	public static void onDied(LivingEntity target, DamageSource source, float damage) {
		if (book == null || config == null) return;
		WoundRules rules = config.getRules();
		if (!rules.isEnabled() || rules.getCorpseSeconds() <= 0 || target instanceof Player && !rules.isPlayerCorpses()) return;
		BodyProfile bodyProfile = profiles.forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
		if (bodyProfile == null || !bodyProfile.isCorpse()) return;
		Vec3 push = Vec3.ZERO;
		Entity by = source.getDirectEntity() != null ? source.getDirectEntity() : source.getEntity();
		if (by != null && by != target) push = target.position().subtract(by.position());
		else if (source.getSourcePosition() != null) push = target.position().subtract(source.getSourcePosition());
		if (push.horizontalDistanceSqr() < 1e-6) push = target.getDeltaMovement();
		if (push.horizontalDistanceSqr() < 1e-6) push = Vec3.directionFromRotation(0, target.yBodyRot + 180);
		push = new Vec3(push.x, 0, push.z).normalize();
		float strength = (float) Math.min(1.5, Math.min(MAX_BLOW, damage) / 20.0 + 0.25);
		int ticks = (int) Math.round(rules.getCorpseSeconds() * 20);
		// a mob's body keeps its entity id on the clients; a player comes back under its id, its body gets one of its own
		int corpseId = target instanceof Player ? nextCorpseId-- : target.getId();
		BodyProfile profile = profiles.forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
		if (target.level() instanceof ServerLevel level) {
			// clients keep a body three times its time at most, while someone looks at it
			Corpse first = new Corpse(level, target.position(), book.copyOf(book.get(target.getUUID()), profile),
					level.getGameTime() + ticks * 3L);
			CORPSES.put(corpseId, first);
			RECOPIES.add(new Recopy(corpseId, first, first.state().getRevision(), target.getUUID(), profile, level.getServer().getTickCount() + 2));
		}
		byte[] message = Protocol.corpse(target.getId(), (float) push.x, (float) push.z, strength, RANDOM.nextInt(), ticks, corpseId);
		broadcast(target, message);
	}

	/**
	 * A blast: rays from where it went off tear the side of the body facing it, limbs may fly.
	 */
	private static void blast(LivingEntity target, DamageSource source, WoundState state, float lost, WoundRules rules,
							  boolean wasLegless, boolean wasHeadless, boolean player) {
		Vec3 from = source.getSourcePosition() != null ? source.getSourcePosition() : target.position().add(0, 0.5, 0);
		var pose = FabricHits.pose(target).crawling(state, rules);
		var hits = HitLocator.blast(state, pose, from.x, from.y, from.z, target.getBbWidth(), target.getBbHeight(), rules.getExplosionWounds(), RANDOM);
		// a player the blast killed loses the head, thrown away from where it went off
		var head = state.getProfile().head();
		float[] headFacing = player && target.isDeadOrDying() && rules.isExplosionDeathTakesHead() && head != null
				? HitLocator.facing(state.getProfile(), head, pose, from.x, from.y, from.z) : null;
		for (byte[] message : book.blast(target.getUUID(), target.getId(), state, hits, lost, headFacing, rules)) broadcast(target, message);
		if (player && book.get(target.getUUID()) != null) PLAYERS.add(target.getUUID());
		Consequences.apply(target, source, state, wasLegless, wasHeadless, rules);
	}

	/**
	 * Clears an entity, for commands and for anything that heals a body completely.
	 */
	public static void heal(Entity entity) {
		if (entity instanceof LivingEntity living) Consequences.clear(living);
		if (book != null && book.clear(entity.getUUID())) {
			PLAYERS.remove(entity.getUUID());
			broadcast(entity, Protocol.clear(entity.getId()));
		}
	}

	private static void resendSelf(ServerPlayer player) {
		if (book == null || !MODDED.contains(player.getUUID())) return;
		WoundState state = book.get(player.getUUID());
		send(player, state == null ? Protocol.clear(player.getId()) : Protocol.state(player.getId(), state));
	}

	/**
	 * After the hello: everything the player may already see was tracked before the server knew the mod is there.
	 */
	private static void sendKnownAround(ServerPlayer player) {
		ServerLevel level = player.level();
		for (UUID id : List.copyOf(book.ids())) {
			Entity entity = level.getEntity(id);
			if (entity == null || entity.distanceToSqr(player) > RESEND_RANGE * RESEND_RANGE) continue;
			send(player, Protocol.state(entity.getId(), book.get(id)));
		}
	}

	// for the public API (ru.deelter.chopchop.api), not for other mods directly

	public static void broadcastApi(Entity entity, byte[] message) {
		broadcast(entity, message);
	}

	public static WoundBook book() {
		return book;
	}

	public static Profiles profilesApi() {
		return profiles;
	}

	public static BodyPose pose(LivingEntity entity, WoundState state) {
		return FabricHits.pose(entity).crawling(state, rules());
	}

	public static void consequences(LivingEntity target, DamageSource source, WoundState state, boolean wasLegless, boolean wasHeadless) {
		if (config == null) return;
		if (target instanceof Player && book.get(target.getUUID()) != null) PLAYERS.add(target.getUUID());
		Consequences.apply(target, source, state, wasLegless, wasHeadless, config.getRules());
	}

	private static void broadcast(Entity entity, byte[] message) {
		for (ServerPlayer player : PlayerLookup.tracking(entity)) {
			if (MODDED.contains(player.getUUID())) send(player, message);
		}
		if (entity instanceof ServerPlayer self && MODDED.contains(self.getUUID())) send(self, message);
	}

	private static void send(ServerPlayer player, byte[] message) {
		if (ServerPlayNetworking.canSend(player, ChopPayload.TYPE)) ServerPlayNetworking.send(player, new ChopPayload(message));
	}

	private static Path storage(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("chopchop-wounds.json");
	}

	private static void savePlayers(MinecraftServer server) {
		Map<String, String> saved = new LinkedHashMap<>();
		for (UUID id : PLAYERS) {
			WoundState state = book.get(id);
			if (state != null) saved.put(id.toString(), WoundBook.encode(state));
		}
		try (Writer writer = Files.newBufferedWriter(storage(server))) {
			GSON.toJson(saved, writer);
		} catch (IOException e) {
			ChopChop.LOG.warn("Can't save player wounds", e);
		}
	}

	private static void loadPlayers(MinecraftServer server) {
		Path file = storage(server);
		if (!Files.exists(file)) return;
		try (Reader reader = Files.newBufferedReader(file)) {
			Map<String, String> saved = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {
			}.getType());
			if (saved == null) return;
			saved.forEach((id, data) -> {
				try {
					UUID uuid = UUID.fromString(id);
					book.put(uuid, book.decode(data));
					if (book.get(uuid) != null) PLAYERS.add(uuid);
				} catch (IllegalArgumentException ignored) {
				}
			});
		} catch (IOException | RuntimeException e) {
			ChopChop.LOG.warn("Can't load player wounds", e);
		}
	}
	/**
	 * Kills an entity with a blow of the mod itself, for the API: no new wounds from it.
	 */
	public static void finish(net.minecraft.world.entity.LivingEntity entity, net.minecraft.server.level.ServerLevel level,
							  net.minecraft.world.damagesource.DamageSource source) {
		Consequences.finish(entity, level, source);
	}
}
