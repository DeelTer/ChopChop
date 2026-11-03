package ru.deelter.chopchop;

import lombok.Getter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.deelter.chopchop.core.profile.Profiles;
import ru.deelter.chopchop.net.ChopPayload;
import ru.deelter.chopchop.server.ChopServer;

/**
 * Common entry point: the channel, the body profiles and the server side, which runs on a dedicated Fabric server
 * and on the integrated server of a singleplayer world alike.
 */
public final class ChopChop implements ModInitializer {
	public static final String ID = "chopchop";
	public static final Logger LOG = LoggerFactory.getLogger("ChopChop");

	@Getter
	private static Profiles profiles;

	@Override
	public void onInitialize() {
		profiles = Profiles.loadBuiltin();
		PayloadTypeRegistry.serverboundPlay().register(ChopPayload.TYPE, ChopPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ChopPayload.TYPE, ChopPayload.CODEC);
		ChopServer.init();
	}
}
