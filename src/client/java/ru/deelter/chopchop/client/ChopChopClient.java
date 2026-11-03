package ru.deelter.chopchop.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.Crawling;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.wound.WoundState;
import net.minecraft.client.Minecraft;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.client.render.BoneMesh;
import ru.deelter.chopchop.client.render.Debris;
import ru.deelter.chopchop.client.render.HeadCam;
import ru.deelter.chopchop.client.render.KillCam;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.net.ChopPayload;

import java.io.IOException;

/**
 * Client entry point: listens to the server, keeps what it said, draws it.
 */
public final class ChopChopClient implements ClientModInitializer {
	private static boolean serverSpeaks;
	/**
	 * the rules of the server the client needs, from its hello
	 */
	private static final WoundRules SERVER_RULES = new WoundRules();
	private static boolean helloSent;

	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		BoneMesh.load();

		ClientPlayNetworking.registerGlobalReceiver(ChopPayload.TYPE, (payload, context) -> receive(payload.data()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			serverSpeaks = false;
			ClientWounds.clearAll();
			Debris.clear();
			Effects.clear();
			Corpses.clear();
			// resource packs may have changed since the last world: the bones texture is read again
			ru.deelter.chopchop.client.render.SkeletonTexture.reset();
			helloSent = false;
			// a server without ChopChop does not listen on the channel, and hears nothing
			if (ClientPlayNetworking.canSend(ChopPayload.TYPE)) sayHello();
		});
		// Paper names the channels it listens on only after the join: the hello waits for that
		ServerboundPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
			if (channels.contains(ChopPayload.TYPE.id())) client.execute(ChopChopClient::sayHello);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			serverSpeaks = false;
			ClientWounds.clearAll();
			Debris.clear();
			Effects.clear();
			Corpses.clear();
			ru.deelter.chopchop.client.render.AnimatedPose.clear();
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> ClientWounds.clear(entity.getId()));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientWounds.tick(client);
			Debris.tick(client);
			Corpses.tick(client);
			ru.deelter.chopchop.client.render.AnimatedPose.tick();
			Effects.tick(client);
			HeadCam.tick(client);
			KillCam.tick(client);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(Debris::submit);
		LevelRenderEvents.COLLECT_SUBMITS.register(Corpses::submit);
		Crawling.client = player -> {
			if (player != Minecraft.getInstance().player || !serverSpeaks) return false;
			WoundState own = ClientWounds.get(player.getId());
			return own != null && SERVER_RULES.crawls() && own.legless();
		};

		if (FabricLoader.getInstance().isModLoaded("modelinteractions")) {
			ChopChop.LOG.info("ModelInteractions found: wounds follow the parts it bends, cut off parts can't be grabbed");
		}
	}

	private static void sayHello() {
		if (helloSent || !ClientPlayNetworking.canSend(ChopPayload.TYPE)) return;
		helloSent = true;
		ClientPlayNetworking.send(new ChopPayload(Protocol.hello()));
	}

	/**
	 * What the client knows of the rules of the server: only the parts that change how bodies are drawn.
	 */
	public static WoundRules serverRules() {
		return SERVER_RULES;
	}

	public static boolean serverSpeaks() {
		return serverSpeaks;
	}

	private static void receive(byte[] data) {
		Protocol.Message message;
		try {
			message = Protocol.readClientbound(data, ChopChop.getProfiles());
		} catch (IOException | RuntimeException e) {
			ChopChop.LOG.warn("Dropped a broken ChopChop message", e);
			return;
		}
		switch (message) {
			case Protocol.Message.Hello hello -> {
				serverSpeaks = hello.version() == Protocol.VERSION;
				SERVER_RULES.setLegless((hello.flags() & Protocol.FLAG_CRAWL) != 0 ? "crawl" : "none");
				ChopChop.LOG.info("Server speaks ChopChop protocol {}", hello.version());
			}
			case Protocol.Message.State state -> ClientWounds.put(state.entityId(), state.state());
			case Protocol.Message.Clear clear -> ClientWounds.clear(clear.entityId());
			case Protocol.Message.Corpse corpse -> Corpses.add(corpse);
			case Protocol.Message.Event event -> {
				boolean cut = event.kind() == Protocol.EVENT_SEVER || event.kind() == Protocol.EVENT_BLAST;
				if (cut && event.severedAt() >= 0 && ClientConfig.get().isDebris()) {
					boolean blast = event.kind() == Protocol.EVENT_BLAST;
					ClientWounds.noteSever(event.entityId(), event.part(), event.severedAt(), blast, blast ? Effects.worldPoint(event) : null);
					var player = Minecraft.getInstance().player;
					if (event.severedAt() == 0 && player != null && event.entityId() == player.getId()) HeadCam.expect();
					else if (event.severedAt() == 0 && player != null && event.attacker() == player.getId()) KillCam.expect(event.entityId());
				}
				Effects.play(event);
			}
		}
	}
}
