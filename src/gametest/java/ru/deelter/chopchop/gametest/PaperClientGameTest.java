package ru.deelter.chopchop.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import ru.deelter.chopchop.client.ChopChopClient;
import ru.deelter.chopchop.client.ClientWounds;
import ru.deelter.chopchop.core.wound.WoundState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Against a real Paper server with the plugin: {@code gradlew runClientGameTest -PpaperPort=25599
 * -PpaperConsole=<file the server reads its console from>}. Skipped otherwise.
 * <p>
 * Hits are dealt by the server console with {@code /damage ... by <player>}, which Paper reports as a melee attack
 * of that player: the plugin finds where on the body it landed from where the player looks, as for a real swing.
 */
public class PaperClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		String port = System.getProperty("chopchop.paperPort");
		String console = System.getProperty("chopchop.paperConsole");
		if (port == null || console == null) return;
		Path consoleFile = Path.of(console);

		context.runOnClient(client -> ConnectScreen.startConnecting(new TitleScreen(), client,
				ServerAddress.parseString("localhost:" + port), new ServerData("paper", "localhost:" + port, ServerData.Type.OTHER),
				false, null));
		context.waitFor(client -> client.level != null && client.player != null, 1200);
		context.waitTicks(40);
		if (!context.computeOnClient(client -> ChopChopClient.serverSpeaks())) throw new AssertionError("Paper plugin did not say hello");
		// the world of an earlier run: whatever the player came back to, start alive, whole and alone
		run(consoleFile, "gamerule spawn_monsters false", "gamerule spawn_mobs false", "gamerule doMobSpawning false",
				"kill @e[type=!player]", "difficulty peaceful");
		context.runOnClient(client -> {
			if (client.player.isDeadOrDying()) client.player.respawn();
		});
		context.waitTicks(20);
		run(consoleFile, "chopchop heal Player0", "effect give @a instant_health 1 10 true", "difficulty easy");
		context.runOnClient(client -> client.gui.setScreen(null));
		context.waitTicks(10);

		run(consoleFile, "gamemode survival @a", "time set 18000", "effect give @a night_vision infinite 0 true",
				"kill @e[type=!player]", "tp @a 0 -60 0 0 0",
				"item replace entity @a weapon.mainhand with diamond_sword",
				"summon zombie 0 -60 2.2 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:[\"chop\"],Rotation:[180f,0f]}");
		context.waitTicks(5);
		run(consoleFile, "attribute @e[tag=chop,limit=1] minecraft:max_health base set 400", "effect give @e[tag=chop] instant_damage 1 20 true");
		context.waitTicks(30);

		float[][] aims = {{-12, 8}, {12, 8}, {-12, 8}, {12, 8}, {-12, 8}, {12, 8}, {0, 18}, {-6, 40}, {6, 40}};
		for (float[] aim : aims) {
			context.runOnClient(client -> {
				client.player.setYRot(aim[0]);
				client.player.setXRot(aim[1]);
			});
			context.waitTicks(3);
			run(consoleFile, "damage @e[tag=chop,limit=1] 6 minecraft:player_attack by @p");
			context.waitTicks(12);
		}
		// the zombie hits back: a mob attack on the player
		for (int i = 0; i < 3; i++) {
			run(consoleFile, "damage @p 2 minecraft:mob_attack by @e[tag=chop,limit=1]");
			context.waitTicks(12);
		}
		context.waitTicks(10);

		String summary = context.computeOnClient(client -> {
			int zombieWounds = -1, zombieSevered = 0, playerWounds = -1;
			for (Entity entity : client.level.entitiesForRendering()) {
				if (!(entity instanceof Zombie)) continue;
				WoundState state = ClientWounds.get(entity.getId());
				if (state == null) continue;
				zombieWounds = 0;
				for (int p = 0; p < state.partCount(); p++) {
					zombieWounds += state.part(p).carvedCount();
					if (state.part(p).severed()) zombieSevered++;
				}
			}
			WoundState own = ClientWounds.get(client.player.getId());
			if (own != null) {
				playerWounds = 0;
				for (int p = 0; p < own.partCount(); p++) playerWounds += own.part(p).carvedCount();
			}
			return "zombieCarved=" + zombieWounds + " zombieSevered=" + zombieSevered + " playerCarved=" + playerWounds;
		});
		System.out.println("[ChopChop paper gametest] " + summary);

		context.runOnClient(client -> {
			client.player.setYRot(0);
			client.player.setXRot(15);
		});
		context.waitTicks(5);
		context.takeScreenshot("paper-zombie");
		context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
		context.waitTicks(5);
		context.takeScreenshot("paper-player-front");
		context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));

		if (summary.contains("zombieCarved=-1")) throw new AssertionError("No zombie wounds from Paper: " + summary);
		if (summary.contains("zombieSevered=0")) throw new AssertionError("No severed limb from Paper: " + summary);
		if (summary.contains("playerCarved=-1")) throw new AssertionError("No player wounds from Paper: " + summary);

		// the zombie takes a sword, looks straight ahead, at the height of the player's eyes, and chops: the head flies on the third chop that lands
		// (the first may meet the invulnerability left by the hits before), the camera with it
		run(consoleFile, "kill @e[type=!player,tag=!chop]", "item replace entity @e[tag=chop] weapon.mainhand with iron_sword", "tp @a 0 -60 0 0 0");
		context.waitTicks(10);
		for (int i = 0; i < 4; i++) {
			run(consoleFile, "damage @p 1 minecraft:mob_attack by @e[tag=chop,limit=1]");
			context.waitTicks(16);
		}
		boolean cam = context.computeOnClient(client -> ru.deelter.chopchop.client.render.HeadCam.active());
		context.waitTicks(40);
		context.takeScreenshot("paper-headcam");
		boolean dead = context.computeOnClient(client -> client.player.isDeadOrDying());
		System.out.println("[ChopChop paper gametest] headcam=" + cam + " dead=" + dead);
		if (!cam) throw new AssertionError("No head cam on Paper");
		if (!dead) throw new AssertionError("A headless player should die on Paper");
		context.runOnClient(client -> client.player.respawn());
		context.waitTicks(20);
		context.runOnClient(client -> client.disconnectFromWorld(net.minecraft.network.chat.Component.empty()));
		context.setScreen(TitleScreen::new);
	}

	private static void run(Path console, String... commands) {
		try {
			Files.writeString(console, String.join("\n", commands) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
