package ru.deelter.chopchop.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.client.ClientWounds;
import ru.deelter.chopchop.client.render.Debris;
import ru.deelter.chopchop.core.wound.WoundState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a world, hits a zombie, a skeleton and a pig with a sword, shoots the player in the arm, and checks that
 * the client got the wounds from the server and draws them. Screenshots go to
 * {@code build/run/clientGameTest/screenshots} for a look by eye.
 */
public class ChopClientGameTest implements FabricClientGameTest {
	private static final double[] COLUMNS = {-4, 0, 4};

	@Override
	public void runTest(ClientGameTestContext context) {
		// the run against Paper has a test of its own
		if (System.getProperty("chopchop.paperPort") != null) return;
		writeRules();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getServer().runCommand("difficulty easy");
			world.getServer().runCommand("gamemode survival @a");
			world.getServer().runCommand("time set 18000");
			world.getServer().runCommand("gamerule advance_time false");
			world.getServer().runCommand("effect give @a night_vision infinite 0 true");
			context.waitTicks(20);

			Vec3 base = world.getServer().computeOnServer(server -> player(server).position());
			// first: it needs no mobs, and the scenes after it differ between the versions of the game
			limbRules(context, world, base);
			selfInF5(context, world, base);
			List<Integer> ids = world.getServer().computeOnServer(server -> {
				ServerLevel level = player(server).level();
				List<Integer> list = new ArrayList<>();
				list.add(spawn(level, EntityTypes.ZOMBIE, base.add(COLUMNS[0], 0, 2.2)));
				list.add(spawn(level, EntityTypes.SKELETON, base.add(COLUMNS[1], 0, 2.2)));
				list.add(spawn(level, EntityTypes.PIG, base.add(COLUMNS[2], 0, 2.0)));
				return list;
			});
			context.waitTicks(10);

			// sword hits: where the player looks decides where they land
			float[][] aims = {{-12, 8}, {12, 8}, {-12, 8}, {12, 8}, {-12, 8}, {12, 8}, {-6, 40}, {6, 40}, {0, 18}, {0, 2}};
			for (int column = 0; column < ids.size(); column++) {
				int id = ids.get(column);
				double x = COLUMNS[column];
				for (float[] aim : aims) {
					world.getServer().runOnServer(server -> {
						ServerPlayer player = player(server);
						Entity target = player.level().getEntity(id);
						if (!(target instanceof LivingEntity living)) return;
						player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
						player.teleportTo(player.level(), base.x + x, base.y, base.z, java.util.Set.of(), aim[0], aim[1], true);
						player.resetAttackStrengthTicker();
						ru.deelter.chopchop.version.Entities.clearInvulnerability(living);
						player.attack(target);
					});
					context.waitTicks(3);
				}
			}

			// arrows into the right arm of the player, from behind, with nothing in between
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				player.teleportTo(player.level(), base.x, base.y, base.z - 6, java.util.Set.of(), 0, 20, true);
				player.setYBodyRot(0);
				player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			});
			context.waitTicks(5);
			for (int i = 0; i < 4; i++) {
				world.getServer().runOnServer(server -> {
					ServerPlayer player = player(server);
					ru.deelter.chopchop.version.Entities.clearInvulnerability(player);
					Arrow arrow = new Arrow(EntityTypes.ARROW, player.level());
					// facing south, the right arm is to the west (-x); shot from the north, behind the player. The
					// arrow is where it would be in the tick it hits, and hurts through the arrow damage source
					Vec3 target = player.position().add(-0.33, 1.0, 0);
					Vec3 from = target.add(0, 0.05, -0.6);
					arrow.setPos(from);
					arrow.setDeltaMovement(target.subtract(from).normalize().scale(2.2));
					player.hurtServer(player.level(), player.level().damageSources().arrow(arrow, null), 3);
				});
				context.waitTicks(12);
			}
			context.waitTicks(10);

			Summary summary = context.computeOnClient(client -> {
				Summary s = new Summary();
				for (int id : ids) {
					WoundState state = ClientWounds.get(id);
					if (state == null) continue;
					s.wounded++;
					for (int p = 0; p < state.partCount(); p++) {
						s.carved += state.part(p).carvedCount();
						if (state.part(p).severed()) s.severed++;
					}
				}
				s.debris = Debris.size();
				WoundState own = client.player == null ? null : ClientWounds.get(client.player.getId());
				s.player = own != null;
				if (own != null) for (int p = 0; p < own.partCount(); p++) s.playerCarved += own.part(p).carvedCount();
				return s;
			});
			System.out.println("[ChopChop gametest] " + summary);

			// a close look at each mob from the front-left
			for (int column = 0; column < ids.size(); column++) {
				double x = COLUMNS[column];
				world.getServer().runOnServer(server -> player(server).teleportTo(player(server).level(), base.x + x + 0.9, base.y, base.z + 0.9,
						java.util.Set.of(), 45, 25, true));
				context.waitTicks(4);
				context.takeScreenshot("chopchop-mob-" + column);
			}
			// the player, in third person from the front and from behind, then the first person hands
			world.getServer().runOnServer(server -> player(server).teleportTo(player(server).level(), base.x, base.y, base.z - 6,
					java.util.Set.of(), 0, 10, true));
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
			context.waitTicks(5);
			context.takeScreenshot("chopchop-player-front");
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(5);
			context.takeScreenshot("chopchop-player-back");
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			context.waitTicks(5);
			context.takeScreenshot("chopchop-first-person");

			// a mob may already have lost its head, run off and died: it is no longer there to count
			if (summary.wounded < 2) throw new AssertionError("Expected wounds on the mobs on the client, got " + summary);
			if (summary.severed < 1) throw new AssertionError("Expected a severed limb, got " + summary);
			if (summary.debris < 1) throw new AssertionError("Expected a falling piece, got " + summary);
			if (!summary.player) throw new AssertionError("Expected the arrows to wound the player, got " + summary);

			crawl(context, world, base, ids.getFirst());
			killCamSkippedUnderThreat(context, world, base);
			killCam(context, world, base);
			fatalities(context, world, base);
			tnt(context, world, base);
			blownUp(context, world, base);
			ownCorpse(context, world, base);
			decapitate(context, world, base);
		}
	}

	/**
	 * Chop the legs of the zombie until both are off: it lies down, slower, and the client draws it lying.
	 */
	private static void crawl(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base, int zombie) {
		for (int i = 0; i < 30; i++) {
			boolean legless = context.computeOnClient(client -> {
				WoundState state = ClientWounds.get(zombie);
				return state != null && state.legless();
			});
			if (legless) break;
			float yaw = i % 2 == 0 ? -7 : 7;
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				Entity target = player.level().getEntity(zombie);
				if (!(target instanceof LivingEntity living)) return;
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_AXE));
				player.teleportTo(player.level(), base.x + COLUMNS[0], base.y, base.z, java.util.Set.of(), yaw, 50, true);
				player.resetAttackStrengthTicker();
				ru.deelter.chopchop.version.Entities.clearInvulnerability(living);
				player.attack(target);
			});
			context.waitTicks(3);
		}
		boolean lies = world.getServer().computeOnServer(server -> player(server).level().getEntity(zombie) instanceof Mob mob
				&& ru.deelter.chopchop.server.ChopServer.lies(mob));
		world.getServer().runOnServer(server -> player(server).teleportTo(player(server).level(), base.x + COLUMNS[0] + 2.2, base.y + 0.3,
				base.z + 2.2, java.util.Set.of(), 90, 25, true));
		context.waitTicks(6);
		context.takeScreenshot("chopchop-crawling-zombie");
		if (!context.computeOnClient(client -> ClientWounds.get(zombie) != null && ClientWounds.get(zombie).legless())) {
			throw new AssertionError("The zombie still has a leg");
		}
		if (!lies) throw new AssertionError("A zombie without legs should lie still on the server");
		// lying there, one chop at its head is enough
		world.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			LivingEntity target = (LivingEntity) player.level().getEntity(zombie);
			var state = ru.deelter.chopchop.server.ChopServer.wounds(target);
			var pose = new ru.deelter.chopchop.core.hit.BodyPose(target.getX(), target.getY(), target.getZ(), target.yBodyRot, target.getScale(), 0)
					.crawling(state, ru.deelter.chopchop.server.ChopServer.rules());
			var head = ru.deelter.chopchop.core.hit.HitLocator.toWorld(pose, 0, -4, 0);
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
			// from right above the head: from the side an arm lying next to it would take the blow
			player.teleportTo(player.level(), head.x, head.y + 0.6, head.z - 0.3, java.util.Set.of(), 0, 80, true);
			Vec3 eye = player.getEyePosition();
			double dx = head.x - eye.x, dy = head.y - eye.y, dz = head.z - eye.z;
			player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
			player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
			player.resetAttackStrengthTicker();
			ru.deelter.chopchop.version.Entities.clearInvulnerability(target);
			player.attack(target);
		});
		context.waitTicks(6);
		context.takeScreenshot("chopchop-lying-headless");
		if (!context.computeOnClient(client -> ClientWounds.get(zombie) != null && ClientWounds.get(zombie).headless())) {
			throw new AssertionError("One chop at a lying zombie's head should take it");
		}
		// that was the player's chop: let its kill cam end before the next scene
		context.waitTicks(70);
	}

	/**
	 * A skeleton nearby is after the player: taking a head now must not take the camera away.
	 */
	private static void killCamSkippedUnderThreat(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		int[] ids = world.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x + 14, base.y, base.z, java.util.Set.of(), 0, 0, true);
			int victim = spawn(player.level(), EntityTypes.ZOMBIE, base.add(14, 0, 2.2));
			int threat = spawn(player.level(), EntityTypes.SKELETON, base.add(17, 0, -2));
			((Mob) player.level().getEntity(threat)).setTarget(player);
			return new int[]{victim, threat};
		});
		context.waitTicks(5);
		chop(context, world, base.add(14, 0, 0), ids[0]);
		context.waitTicks(10);
		boolean active = context.computeOnClient(client -> ru.deelter.chopchop.client.render.KillCam.active());
		boolean headless = context.computeOnClient(client -> ClientWounds.get(ids[0]) != null && ClientWounds.get(ids[0]).headless());
		System.out.println("[ChopChop gametest] underThreat killcam=" + active + " headless=" + headless);
		world.getServer().runOnServer(server -> {
			Entity threat = player(server).level().getEntity(ids[1]);
			if (threat != null) threat.discard();
		});
		if (!headless) throw new AssertionError("The head did not come off");
		if (active) throw new AssertionError("A kill cam played with a monster after the player");
		context.waitTicks(70);
	}

	private static void chop(ClientGameTestContext context, TestSingleplayerContext world, Vec3 at, int victim) {
		for (int i = 0; i < 3; i++) {
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				Entity target = player.level().getEntity(victim);
				if (!(target instanceof LivingEntity living)) return;
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
				player.teleportTo(player.level(), at.x, at.y, at.z, java.util.Set.of(), 0, -2, true);
				player.resetAttackStrengthTicker();
				ru.deelter.chopchop.version.Entities.clearInvulnerability(living);
				player.attack(target);
			});
			context.waitTicks(4);
		}
	}

	/**
	 * TNT kills the player: the head always comes off and flies away from the blast, the camera in it.
	 */
	/**
	 * The player dies, a player head worn: the camera leaves the eyes to look at the own body, the head item lies
	 * on the body's head as it falls.
	 */
	/**
	 * What a player without some limbs can still do, on its own client: wounds put on the client only, as the
	 * server would send them.
	 */
	/**
	 * The own body in third person shows its wounds: the right arm off and the body cut, seen from the front.
	 */
	private static void selfInF5(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		world.getServer().runOnServer(server -> player(server).teleportTo(player(server).level(), base.x - 32, base.y, base.z, java.util.Set.of(), 0, 0, true));
		context.waitTicks(5);
		context.runOnClient(client -> {
			var profile = ru.deelter.chopchop.ChopChop.getProfiles().byId("player");
			WoundState state = new WoundState(profile);
			state.part(profile.part("right_arm").getIndex()).sever(3);
			var body = state.part(profile.part("body").getIndex());
			for (int x = 1; x < 7; x++) for (int z = 0; z < 2; z++) body.carve(x, 5, z);
			ClientWounds.put(client.player.getId(), state);
			client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
		});
		context.waitTicks(5);
		context.takeScreenshot("chopchop-self-f5");
		context.runOnClient(client -> {
			ClientWounds.clear(client.player.getId());
			client.options.setCameraType(CameraType.FIRST_PERSON);
		});
	}

	private static void limbRules(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x - 32, base.y, base.z, java.util.Set.of(), 0, 60, true);
			player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
			player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BREAD));
		});
		context.waitTicks(5);
		String result = context.computeOnClient(client -> {
			var player = client.player;
			var profile = ru.deelter.chopchop.ChopChop.getProfiles().byId("player");
			WoundState state = new WoundState(profile);
			String main = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? "right_arm" : "left_arm";
			String off = main.equals("right_arm") ? "left_arm" : "right_arm";
			StringBuilder s = new StringBuilder();
			// main hand off: it eats nothing, the other hand still does
			state.part(profile.part(main).getIndex()).sever(7);
			ClientWounds.put(player.getId(), state);
			s.append(client.gameMode.useItem(player, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.PASS ? "" : "main-used ");
			s.append(ru.deelter.chopchop.client.Limbs.offHandDigs() ? "" : "no-offhand-dig ");
			// both hands off: a leg digs, and kicks
			state.part(profile.part(off).getIndex()).sever(7);
			s.append(ru.deelter.chopchop.client.Limbs.diggingLeg() == 0 ? "" : "no-leg-dig ");
			ru.deelter.chopchop.client.version.ClientSwings.swing(player);
			s.append(ru.deelter.chopchop.client.render.Kicks.kicking(player.getId()) ? "" : "no-kick ");
			// a leg off: no sprint
			state.part(profile.part("right_leg").getIndex()).sever(7);
			player.setSprinting(true);
			s.append(player.isSprinting() ? "sprints " : "");
			// everything off: nothing digs
			state.part(profile.part("left_leg").getIndex()).sever(7);
			s.append(ru.deelter.chopchop.client.Limbs.canDig() ? "digs-limbless " : "");
			ClientWounds.clear(player.getId());
			return s.toString().trim();
		});
		System.out.println("[ChopChop gametest] limbs " + (result.isEmpty() ? "ok" : result));
		if (!result.isEmpty()) throw new AssertionError("Limb rules: " + result);
	}

	private static void ownCorpse(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x - 26, base.y, base.z, java.util.Set.of(), 0, 0, true);
			player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.PLAYER_HEAD));
			ru.deelter.chopchop.version.Entities.clearInvulnerability(player);
			player.hurtServer(player.level(), player.level().damageSources().generic(), 1000);
		});
		context.waitTicks(10);
		boolean cam = context.computeOnClient(client -> ru.deelter.chopchop.client.render.DeathCam.active());
		context.waitTicks(30);
		context.runOnClient(client -> client.gui.setScreen(null));
		context.takeScreenshot("chopchop-own-corpse");
		System.out.println("[ChopChop gametest] ownCorpse deathcam=" + cam);
		context.runOnClient(client -> client.player.respawn());
		context.waitTicks(40);
		boolean over = context.computeOnClient(client -> !ru.deelter.chopchop.client.render.DeathCam.active());
		world.getServer().runOnServer(server -> player(server).setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY));
		if (!cam) throw new AssertionError("No view of the own body after death");
		if (!over) throw new AssertionError("The view of the own body stayed after respawn");
	}

	private static void blownUp(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		world.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x - 20, base.y, base.z, java.util.Set.of(), 0, 0, true);
			player.setHealth(2);
			var tnt = EntityTypes.TNT.create(player.level(), EntitySpawnReason.COMMAND);
			// in front, to the left: the head flies back and to the right
			tnt.setPos(base.x - 19.4, base.y, base.z + 1.2);
			tnt.setFuse(1);
			player.level().addFreshEntity(tnt);
		});
		context.waitTicks(8);
		context.takeScreenshot("chopchop-blown-up-flying");
		boolean cam = context.computeOnClient(client -> ru.deelter.chopchop.client.render.HeadCam.active());
		boolean headless = context.computeOnClient(client -> client.player != null && ClientWounds.get(client.player.getId()) != null
				&& ClientWounds.get(client.player.getId()).headless());
		boolean dead = context.computeOnClient(client -> client.player.isDeadOrDying());
		context.waitTicks(40);
		context.takeScreenshot("chopchop-blown-up-landed");
		System.out.println("[ChopChop gametest] blownUp dead=" + dead + " headless=" + headless + " headcam=" + cam);
		context.waitTicks(70);
		context.runOnClient(client -> {
			client.player.respawn();
			client.gui.setScreen(null);
		});
		context.waitTicks(40);
		if (!dead) throw new AssertionError("The TNT did not kill the player");
		if (!headless) throw new AssertionError("A player killed by a blast should lose the head");
		if (!cam) throw new AssertionError("No head cam after the blast");
	}

	/**
	 * TNT right next to a zombie: craters on the near side, limbs thrown off.
	 */
	private static void tnt(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		int victim = world.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x - 10, base.y, base.z - 5, java.util.Set.of(), 0, 15, true);
			int id = spawn(player.level(), EntityTypes.ZOMBIE, base.add(-10, 0, 2));
			var tnt = EntityTypes.TNT.create(player.level(), EntitySpawnReason.COMMAND);
			tnt.setPos(base.x - 9.3, base.y, base.z + 2.4);
			tnt.setFuse(1);
			player.level().addFreshEntity(tnt);
			return id;
		});
		context.waitTicks(5);
		context.takeScreenshot("chopchop-tnt-blast");
		context.waitTicks(25);
		context.takeScreenshot("chopchop-tnt-after");
		int[] summary = context.computeOnClient(client -> {
			WoundState state = ClientWounds.get(victim);
			if (state == null) return new int[]{-1, 0};
			int carved = 0, severed = 0;
			for (int p = 0; p < state.partCount(); p++) {
				carved += state.part(p).carvedCount();
				if (state.part(p).severed()) severed++;
			}
			return new int[]{carved, severed};
		});
		System.out.println("[ChopChop gametest] tnt carved=" + summary[0] + " severed=" + summary[1]);
		if (summary[0] <= 0) throw new AssertionError("TNT left no wounds");
		if (summary[1] <= 0) throw new AssertionError("TNT right next to a zombie took no limb");
	}

	/**
	 * The API fatalities: one zombie split from the head down, one cut in half at the waist.
	 */
	private static void fatalities(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		int[] ids = world.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x + 41.5, base.y, base.z - 3, java.util.Set.of(), 0, 10, true);
			return new int[]{spawn(player.level(), EntityTypes.ZOMBIE, base.add(40, 0, 1)), spawn(player.level(), EntityTypes.ZOMBIE, base.add(43, 0, 1))};
		});
		context.waitTicks(10);
		boolean[] done = world.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			LivingEntity a = (LivingEntity) player.level().getEntity(ids[0]);
			LivingEntity b = (LivingEntity) player.level().getEntity(ids[1]);
			return new boolean[]{ru.deelter.chopchop.api.ChopChopApi.split(a, player, false),
					ru.deelter.chopchop.api.ChopChopApi.halve(b, player), a.isAlive(), b.isAlive()};
		});
		context.waitTicks(3);
		context.takeScreenshot("chopchop-fatality-falling");
		context.waitTicks(20);
		context.takeScreenshot("chopchop-fatality-landed");
		System.out.println("[ChopChop gametest] split=" + done[0] + " halve=" + done[1] + " alive=" + done[2] + "," + done[3]);
		if (!done[0] || !done[1]) throw new AssertionError("A fatality cut nothing");
		if (done[2] || done[3]) throw new AssertionError("A fatality left its target alive");
	}

	/**
	 * The player takes a zombie's head with three chops: the camera swings around the flying head and comes back.
	 */
	private static void killCam(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		// far from the mobs of the other scenes: a monster that sees the player would cancel the kill cam
		int victim = world.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x + 30, base.y, base.z, java.util.Set.of(), 0, 0, true);
			return spawn(player.level(), EntityTypes.ZOMBIE, base.add(30, 0, 2.2));
		});
		context.waitTicks(5);
		for (int i = 0; i < 3; i++) {
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				Entity target = player.level().getEntity(victim);
				if (!(target instanceof LivingEntity living)) return;
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
				player.teleportTo(player.level(), base.x + 30, base.y, base.z, java.util.Set.of(), 0, -2, true);
				player.resetAttackStrengthTicker();
				ru.deelter.chopchop.version.Entities.clearInvulnerability(living);
				player.attack(target);
			});
			context.waitTicks(4);
		}
		context.waitTicks(10);
		boolean active = context.computeOnClient(client -> ru.deelter.chopchop.client.render.KillCam.active());
		context.takeScreenshot("chopchop-killcam-orbit");
		context.waitTicks(15);
		context.takeScreenshot("chopchop-killcam-later");
		context.waitTicks(40);
		boolean over = context.computeOnClient(client -> !ru.deelter.chopchop.client.render.KillCam.active());
		System.out.println("[ChopChop gametest] killcam=" + active + " over=" + over);
		if (!active) throw new AssertionError("No kill cam after taking a head");
		if (!over) throw new AssertionError("The kill cam did not end");
	}

	/**
	 * Three chops of a sword-wielding zombie at the player's head: the head flies, the camera rides in it, the death
	 * screen waits, then comes; after respawn the camera and the wounds are back to normal.
	 */
	private static void decapitate(ClientGameTestContext context, TestSingleplayerContext world, Vec3 base) {
		int executioner = world.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.teleportTo(player.level(), base.x, base.y, base.z - 8, java.util.Set.of(), 0, 0, true);
			player.setYBodyRot(0);
			int id = spawn(player.level(), EntityTypes.ZOMBIE, base.add(0, 0, -6.6));
			((Mob) player.level().getEntity(id)).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
			return id;
		});
		context.waitTicks(5);
		for (int i = 0; i < 3; i++) {
			world.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				Mob zombie = (Mob) player.level().getEntity(executioner);
				// the zombie looks the player in the eyes and swings
				Vec3 to = player.getEyePosition().subtract(zombie.getEyePosition());
				float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
				float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
				zombie.setYRot(yaw);
				zombie.setYHeadRot(yaw);
				zombie.setXRot(pitch);
				ru.deelter.chopchop.version.Entities.clearInvulnerability(player);
				player.hurtServer(player.level(), player.level().damageSources().mobAttack(zombie), 2);
			});
			context.waitTicks(4);
		}
		context.waitTicks(4);
		boolean cam = context.computeOnClient(client -> ru.deelter.chopchop.client.render.HeadCam.active());
		context.takeScreenshot("chopchop-headcam-flying");
		context.waitTicks(20);
		boolean heldDeathScreen = context.computeOnClient(client -> !(client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen));
		context.waitTicks(20);
		context.takeScreenshot("chopchop-headcam-landed");
		context.waitTicks(60);
		boolean deathScreen = context.computeOnClient(client -> client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen);
		context.takeScreenshot("chopchop-headcam-deathscreen");
		System.out.println("[ChopChop gametest] headcam=" + cam + " held=" + heldDeathScreen + " deathScreen=" + deathScreen);
		if (!cam) throw new AssertionError("The camera did not go into the head");
		if (!heldDeathScreen) throw new AssertionError("The death screen came before the head landed");
		if (!deathScreen) throw new AssertionError("The death screen never came");
		context.runOnClient(client -> {
			client.player.respawn();
			client.gui.setScreen(null);
		});
		context.waitTicks(40);
		boolean back = context.computeOnClient(client -> !ru.deelter.chopchop.client.render.HeadCam.active()
				&& ClientWounds.get(client.player.getId()) == null);
		if (!back) throw new AssertionError("After respawn the head cam or the wounds stayed");
	}

	private static ServerPlayer player(net.minecraft.server.MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static int spawn(ServerLevel level, EntityType<?> type, Vec3 at) {
		Entity entity = type.create(level, EntitySpawnReason.COMMAND);
		if (entity == null) throw new AssertionError("can't spawn " + type);
		entity.snapTo(at.x, at.y, at.z, 180, 0);
		if (entity instanceof Mob mob) {
			mob.setNoAi(true);
			mob.setPersistenceRequired();
			mob.setYBodyRot(180);
			mob.setYHeadRot(180);
			mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(400);
			mob.setHealth(400);
			mob.setSilent(true);
		}
		level.addFreshEntity(entity);
		return entity.getId();
	}

	/**
	 * Rules that make every hit count, so the test does not depend on luck.
	 */
	private static void writeRules() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("chopchop-server.json");
		String json = """
				{
				  "rules": {
				    "woundChance": 1.0,
				    "severChance": 1.0,
				    "durabilityMultiplier": 0.25,
				    "minSeverDamage": 0.0,
				    "explosionWounds": 12
				  }
				}
				""";
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static final class Summary {
		int wounded, carved, severed, debris, playerCarved;
		boolean player;

		@Override
		public String toString() {
			return "wounded=" + wounded + " carved=" + carved + " severed=" + severed + " debris=" + debris
					+ " player=" + player + " playerCarved=" + playerCarved;
		}
	}
}
