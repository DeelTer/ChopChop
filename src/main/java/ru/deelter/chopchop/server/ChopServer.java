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

	private static ServerConfig config;
	private static WoundBook book;
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

	private ChopServer() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			config = ServerConfig.load();
			// a copy of its own: the assignments of one world must not stay for the next world of the same game
			profiles = Profiles.loadBuiltin();
			config.getEntityProfiles().forEach(profiles::assign);
			book = new WoundBook(profiles, new Random());
			MODDED.clear();
			PLAYERS.clear();
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
			int version = Protocol.readServerbound(payload.data());
			if (version != Protocol.VERSION) return;
			MODDED.add(player.getUUID());
			send(player, Protocol.hello(Protocol.flags(config.getRules())));
			sendKnownAround(player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MODDED.remove(handler.getPlayer().getUUID()));

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
	 * What the server knows of an entity's wounds, null when it is whole.
	 */
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
		if (book == null || config == null) return;
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
		if (source.getEntity() instanceof ServerPlayer killer && "head".equals(profile.part(hit.part()).getName())) {
			// a kill cam only where it does not leave the player helpless
			threats = Consequences.threats(killer, target, rules);
			if (!threats.isEmpty() && "skip".equalsIgnoreCase(rules.getKillCamThreats())) attacker = -1;
		}
		List<byte[]> messages = book.hit(target.getUUID(), target.getId(), state, hit, resolved.weapon(), lost, rules, attacker);
		if (player && book.get(target.getUUID()) != null) PLAYERS.add(target.getUUID());
		for (byte[] message : messages) broadcast(target, message);
		Consequences.apply(target, source, state, wasLegless, wasHeadless, rules);
		if (!wasHeadless && state.headless() && attacker >= 0 && "freeze".equalsIgnoreCase(rules.getKillCamThreats())) {
			Consequences.freeze(threats, rules);
		}
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
		var head = state.getProfile().part("head");
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
}
