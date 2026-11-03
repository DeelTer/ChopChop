package ru.deelter.chopchop.core;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import ru.deelter.chopchop.core.hit.AimCheck;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.Profiles;
import ru.deelter.chopchop.core.rules.WeaponKind;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.server.WoundBook;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundEngine;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
	private static final Profiles PROFILES = Profiles.loadBuiltin();

	/**
	 * Body facing south (yaw 0), the attacker in front of it at +z looking north.
	 */
	private static final BodyPose SOUTH = new BodyPose(0, 64, 0, 0, 1, 0);

	@Test
	void profilesLoad() {
		assertNotNull(PROFILES.forEntity("minecraft:player"));
		assertNotNull(PROFILES.forEntity("minecraft:zombie"));
		assertEquals("bone", PROFILES.forEntity("minecraft:skeleton").getMaterial());
		assertNotNull(PROFILES.forEntity("minecraft:pig"));
		assertNull(PROFILES.forEntity("minecraft:creeper"));
	}

	@Test
	void modelTransformRoundTrips() {
		Vector3d model = HitLocator.toModel(SOUTH, 0.3, 65.2, -0.1, true);
		Vector3d back = HitLocator.toWorld(SOUTH, model.x, model.y, model.z);
		assertEquals(0.3, back.x, 1e-6);
		assertEquals(65.2, back.y, 1e-6);
		assertEquals(-0.1, back.z, 1e-6);
		// feet are at model y 24, the head top at model y -8
		assertEquals(24.016, HitLocator.toModel(SOUTH, 0, 64, 0, true).y, 1e-3);
	}

	@Test
	void frontHitAtChestFindsBody() {
		WoundState state = new WoundState(PROFILES.byId("player"));
		Hit hit = HitLocator.cast(state, SOUTH, 0, 65.1, 2, 0, 0, -1, 4);
		assertNotNull(hit);
		assertEquals("body", state.getProfile().part(hit.part()).getName());
	}

	@Test
	void sideOfTheBodyIsTheRightArm() {
		// facing south, the right arm of the model is to the west (-x)
		WoundState state = new WoundState(PROFILES.byId("player"));
		Hit hit = HitLocator.cast(state, SOUTH, -0.31, 65.0, 2, 0, 0, -1, 4);
		assertNotNull(hit);
		assertEquals("right_arm", state.getProfile().part(hit.part()).getName());
		// low on the arm: the hand end, rows of the lower segment
		Hit low = HitLocator.cast(state, SOUTH, -0.31, 64.85, 2, 0, 0, -1, 4);
		assertTrue(low.y() >= 7, "row " + low.y());
	}

	@Test
	void zombieArmsReachForward() {
		// a zombie holds its arms out in front: a cut from above, in front of its right shoulder, finds the arm
		WoundState state = new WoundState(PROFILES.forEntity("minecraft:zombie"));
		Hit hit = HitLocator.cast(state, SOUTH, -0.31, 66.5, 0.45, 0, -1, 0, 4);
		assertNotNull(hit);
		assertEquals("right_arm", state.getProfile().part(hit.part()).getName());
		assertTrue(hit.y() >= 5, "row " + hit.y());
	}

	@Test
	void secondHitGoesDeeper() {
		WoundState state = new WoundState(PROFILES.byId("player"));
		Hit first = HitLocator.cast(state, SOUTH, 0, 65.1, 2, 0, 0, -1, 4);
		state.part(first.part()).carve(first.x(), first.y(), first.z());
		Hit second = HitLocator.cast(state, SOUTH, 0, 65.1, 2, 0, 0, -1, 4);
		assertEquals(first.part(), second.part());
		assertNotEquals(first.z(), second.z());
		assertTrue(second.depth() > first.depth());
	}

	@Test
	void meleeMissFallsBackToNearestPart() {
		WoundState state = new WoundState(PROFILES.byId("player"));
		// looks past the left of the body
		Hit hit = HitLocator.castOrNearest(state, SOUTH, 1.5, 65.0, 2, 0, 0, -1, 4);
		assertNotNull(hit);
	}

	@Test
	void severRemovesDownstreamAndNeverTwice() {
		BodyProfile profile = PROFILES.byId("player");
		WoundState state = new WoundState(profile);
		int arm = profile.part("right_arm").getIndex();
		PartState part = state.part(arm);
		assertTrue(part.sever(7));
		assertTrue(part.gone(1, 11, 1));
		assertFalse(part.gone(1, 6, 1));
		// a lower cut where it is already gone changes nothing
		assertFalse(part.sever(9));
		// cutting higher takes the rest with it
		assertTrue(part.sever(3));
		assertTrue(part.gone(1, 5, 1));
	}

	@Test
	void severedSegmentCannotBeHit() {
		BodyProfile profile = PROFILES.byId("player");
		WoundState state = new WoundState(profile);
		state.part(profile.part("right_arm").getIndex()).sever(7);
		Hit low = HitLocator.cast(state, SOUTH, -0.31, 64.85, 2, 0, 0, -1, 4);
		assertTrue(low == null || !"right_arm".equals(profile.part(low.part()).getName()) || low.y() < 7);
	}

	@Test
	void swordSeversOnlyAfterDurability() {
		BodyProfile profile = PROFILES.byId("player");
		WoundRules rules = new WoundRules();
		rules.setSeverChance(1.0);
		rules.setWoundChance(0);
		WoundState state = new WoundState(profile);
		int arm = profile.part("right_arm").getIndex();
		Hit hit = new Hit(arm, 1, 9, 1, new float[3], 0);
		Random random = new Random(1);
		assertFalse(WoundEngine.apply(state, hit, WeaponKind.SWORD, 5, rules, random).changed());
		WoundEngine.Outcome outcome = WoundEngine.apply(state, hit, WeaponKind.SWORD, 5, rules, random);
		assertEquals(7, outcome.severedAt());
		// arrows never sever
		WoundState other = new WoundState(profile);
		for (int i = 0; i < 10; i++) WoundEngine.apply(other, hit, WeaponKind.ARROW, 9, rules, random);
		assertFalse(other.part(arm).severed());
	}

	@Test
	void headFliesOnThirdChop() {
		BodyProfile profile = PROFILES.byId("player");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		WoundState state = new WoundState(profile);
		int head = profile.part("head").getIndex();
		Hit hit = new Hit(head, 3, 4, 0, new float[3], 0);
		Random random = new Random(4);
		// an arrow counts for nothing, weak sword hits count all the same
		assertFalse(WoundEngine.apply(state, hit, WeaponKind.ARROW, 9, rules, random).changed());
		assertEquals(-1, WoundEngine.apply(state, hit, WeaponKind.SWORD, 1, rules, random).severedAt());
		assertEquals(-1, WoundEngine.apply(state, hit, WeaponKind.SWORD, 1, rules, random).severedAt());
		assertEquals(0, WoundEngine.apply(state, hit, WeaponKind.AXE, 1, rules, random).severedAt());
		assertTrue(state.headless());
		assertTrue(state.part(head).gone(3, 0, 3));
		rules.setHeads(false);
		WoundState other = new WoundState(profile);
		for (int i = 0; i < 5; i++) WoundEngine.apply(other, hit, WeaponKind.SWORD, 9, rules, random);
		assertFalse(other.headless());
	}

	@Test
	void lastLegProtectedOnlyWhenAsked() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		rules.setSeverChance(1);
		rules.setDurabilityMultiplier(0.01);
		rules.setMinSeverDamage(0);
		rules.setLegless("protect");
		WoundState state = new WoundState(profile);
		Random random = new Random(5);
		int right = profile.part("right_leg").getIndex(), left = profile.part("left_leg").getIndex();
		WoundEngine.apply(state, new Hit(right, 1, 9, 1, new float[3], 0), WeaponKind.AXE, 8, rules, random);
		assertTrue(state.part(right).severed());
		WoundEngine.apply(state, new Hit(left, 1, 9, 1, new float[3], 0), WeaponKind.AXE, 8, rules, random);
		assertFalse(state.part(left).severed());
		assertFalse(state.legless());
		rules.setLegless("crawl");
		WoundEngine.apply(state, new Hit(left, 1, 9, 1, new float[3], 0), WeaponKind.AXE, 8, rules, random);
		assertTrue(state.legless());
		assertTrue(BodyPose.crawls(state, rules));
		// players have no crawl of their own, the game's crawling pose is theirs
		assertNull(PROFILES.byId("player").getCrawl());
	}

	@Test
	void crawlingZombieIsHitOnTheGround() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		WoundState state = new WoundState(profile);
		state.part(profile.part("right_leg").getIndex()).sever(3);
		state.part(profile.part("left_leg").getIndex()).sever(3);
		BodyPose pose = SOUTH.crawling(state, rules);
		assertEquals(-90, pose.pitch());
		// the transform still round-trips with lift and shift
		Vector3d model = HitLocator.toModel(pose, 0.2, 64.1, 0.3, true);
		Vector3d back = HitLocator.toWorld(pose, model.x, model.y, model.z);
		assertEquals(0.2, back.x, 1e-6);
		assertEquals(64.1, back.y, 1e-6);
		assertEquals(0.3, back.z, 1e-6);
		// straight down onto the place it stands: the body lies there, not air above it
		Hit hit = HitLocator.cast(state, pose, 0, 66, 0, 0, -1, 0, 4);
		assertNotNull(hit);
		assertEquals("body", profile.part(hit.part()).getName());
		assertTrue(HitLocator.toWorld(pose, hit.model()[0], hit.model()[1], hit.model()[2]).y < 64.4);
	}

	@Test
	void swordLeavesALevelScar() {
		BodyProfile profile = PROFILES.byId("player");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(1);
		rules.setSevering(false);
		WoundState state = new WoundState(profile);
		// a swing from the front at the chest: the cut runs across the body, level, wider than it is tall
		Hit hit = HitLocator.cast(state, SOUTH, 0, 65.1, 2, 0, 0, -1, 4);
		assertNotNull(hit.slash());
		assertEquals(0, hit.slash()[1], 1e-4, "a level swing cuts level");
		WoundEngine.apply(state, hit, WeaponKind.SWORD, 6, rules, new Random(6));
		PartState body = state.part(hit.part());
		int minX = 99, maxX = -1, minY = 99, maxY = -1;
		for (int x = 0; x < 8; x++) {
			for (int y = 0; y < 12; y++) {
				for (int z = 0; z < 4; z++) {
					if (!body.carved(x, y, z)) continue;
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
		}
		assertTrue(maxX - minX >= 3, "scar is long: " + (maxX - minX + 1));
		assertTrue(maxX - minX > maxY - minY, "scar is longer than it is tall");
	}

	@Test
	void blastTearsTheNearSideAndThrowsLimbsWhenClose() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		Random random = new Random(7);
		// a blast at the feet, in front: craters only, far below the sever damage
		WoundState far = new WoundState(profile);
		var hits = HitLocator.blast(far, SOUTH, 0, 64.2, 3, 0.6, 1.95, 5, random);
		assertFalse(hits.isEmpty());
		var weak = WoundEngine.blast(far, hits, 4, rules, random);
		assertTrue(weak.carved() > 0);
		assertTrue(weak.severed().isEmpty());
		// point blank, huge damage: limbs come off, a head only to a blast that close
		rules.setExplosionSeverChancePerDamage(1);
		WoundState close = new WoundState(profile);
		var near = HitLocator.blast(close, SOUTH, 0, 64.9, 0.8, 0.6, 1.95, 12, random);
		var strong = WoundEngine.blast(close, near, 40, rules, random);
		assertFalse(strong.severed().isEmpty());
		for (int[] cut : strong.severed()) assertTrue(close.part(cut[0]).severed());
	}

	@Test
	void leglessBodyLosesItsHeadOnTheNextChop() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		WoundState state = new WoundState(profile);
		state.part(profile.part("right_leg").getIndex()).sever(3);
		state.part(profile.part("left_leg").getIndex()).sever(3);
		assertTrue(rules.mobsLie());
		Hit head = new Hit(profile.part("head").getIndex(), 3, 4, 3, new float[3], 0);
		assertEquals(0, WoundEngine.apply(state, head, WeaponKind.SWORD, 1, rules, new Random(8)).severedAt());
		// standing, it takes the usual three
		WoundState standing = new WoundState(profile);
		assertEquals(-1, WoundEngine.apply(standing, head, WeaponKind.SWORD, 1, rules, new Random(8)).severedAt());
	}

	@Test
	void blastThatKillsTakesTheHeadFacingIt() {
		BodyProfile profile = PROFILES.byId("player");
		WoundRules rules = new WoundRules();
		WoundState state = new WoundState(profile);
		// a weak blast, nothing would come off; it killed the player, so the head goes anyway
		var outcome = WoundEngine.blast(state, List.of(), 3, true, rules, new Random(9));
		assertTrue(state.headless());
		assertEquals(1, outcome.severed().size());
		// the point it flies away from is on the side of the head facing the blast: here in front of the player
		float[] facing = HitLocator.facing(profile, profile.part("head"), SOUTH, 0, 65.7, 3);
		var at = HitLocator.toWorld(SOUTH, facing[0], facing[1], facing[2]);
		assertTrue(at.z > 0.1, "facing point " + at);
	}

	@Test
	void woundsStayWithinShare() {
		BodyProfile profile = PROFILES.byId("player");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(1);
		rules.setSevering(false);
		WoundState state = new WoundState(profile);
		Random random = new Random(2);
		int body = profile.part("body").getIndex();
		for (int i = 0; i < 200; i++) {
			Hit hit = HitLocator.cast(state, SOUTH, random.nextDouble() * 0.5 - 0.25, 64.8 + random.nextDouble() * 0.7, 2, 0, 0, -1, 4);
			if (hit != null) WoundEngine.apply(state, hit, WeaponKind.AXE, 8, rules, random);
		}
		PartState part = state.part(body);
		assertTrue(part.carvedCount() <= profile.part("body").volume() * rules.getMaxCarvedShare());
		assertTrue(part.carvedCount() > 0);
	}

	@Test
	void protocolRoundTrip() throws Exception {
		BodyProfile profile = PROFILES.byId("pig");
		WoundState state = new WoundState(profile);
		state.part(0).carve(1, 2, 3);
		state.part(2).sever(2);
		state.touch();
		Protocol.Message message = Protocol.readClientbound(Protocol.state(42, state), PROFILES);
		Protocol.Message.State read = assertInstanceOf(Protocol.Message.State.class, message);
		assertEquals(42, read.entityId());
		assertTrue(read.state().part(0).carved(1, 2, 3));
		assertEquals(2, read.state().part(2).getSeveredAt());
		assertEquals(state.getRevision(), read.state().getRevision());
		assertEquals(Protocol.VERSION, Protocol.readServerbound(Protocol.hello()));
		assertThrows(Exception.class, () -> Protocol.readClientbound(new byte[]{9}, PROFILES));
	}

	@Test
	void pigBodyIsTurned() {
		WoundState state = new WoundState(PROFILES.byId("pig"));
		// pig facing south, hit from above at its middle
		Hit hit = HitLocator.cast(state, new BodyPose(0, 64, 0, 0, 1, 0), 0, 66, 0.1, 0, -1, 0, 4);
		assertNotNull(hit);
		assertEquals("body", state.getProfile().part(hit.part()).getName());
	}

	@Test
	void bookKeepsOnlyChanged() {
		WoundBook book = new WoundBook(PROFILES, new Random(3));
		BodyProfile profile = PROFILES.byId("player");
		UUID id = UUID.randomUUID();
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		rules.setSevering(false);
		WoundState state = book.getOrBlank(id, profile);
		List<byte[]> messages = book.hit(id, 1, state, new Hit(1, 1, 1, 0, new float[3], 0), WeaponKind.SWORD, 3, rules, -1);
		assertEquals(1, messages.size());
		assertNull(book.get(id));
		rules.setWoundChance(1);
		messages = book.hit(id, 1, state, new Hit(1, 1, 1, 0, new float[3], 0), WeaponKind.SWORD, 3, rules, -1);
		assertEquals(2, messages.size());
		assertNotNull(book.get(id));
		WoundState decoded = book.decode(WoundBook.encode(state));
		assertTrue(decoded.part(1).carved(1, 1, 0));
	}

	@Test
	void frontBlowsNeverOpenTheBack() {
		for (WeaponKind weapon : new WeaponKind[]{WeaponKind.SWORD, WeaponKind.AXE, WeaponKind.BLUNT}) {
			WoundRules rules = new WoundRules();
			rules.setWoundChance(1);
			rules.setSevering(false);
			WoundState state = new WoundState(PROFILES.byId("zombie"));
			Random random = new Random(1);
			for (double y : new double[]{64.5, 64.7, 64.9, 65.1}) {
				for (double x : new double[]{-0.15, 0, 0.15}) {
					for (int k = 0; k < 4; k++) {
						Hit hit = HitLocator.cast(state, SOUTH, x, y, 2, 0, 0, -1, 4);
						if (hit != null) WoundEngine.apply(state, hit, weapon, 12, rules, random);
					}
				}
			}
			for (int i = 0; i < state.partCount(); i++) {
				PartState part = state.part(i);
				var def = part.getPart();
				for (int x = 0; x < def.gx(); x++) {
					for (int y = 0; y < def.gy(); y++) {
						assertFalse(part.carved(x, y, def.gz() - 1), weapon + " opened the back of " + def.getName());
					}
				}
			}
		}
	}

	@Test
	void killingBlowTakesTheLimb() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		rules.setLethalSeverChance(0.95);
		int arm = profile.part("right_arm").getIndex();
		Hit hit = new Hit(arm, 1, 9, 1, new float[3], 0);
		Random random = new Random(3);
		int lethal = 0, plain = 0;
		for (int i = 0; i < 200; i++) {
			if (WoundEngine.apply(new WoundState(profile), hit, WeaponKind.SWORD, 6, true, rules, random).severedAt() >= 0) lethal++;
			if (WoundEngine.apply(new WoundState(profile), hit, WeaponKind.SWORD, 6, false, rules, random).severedAt() >= 0) plain++;
		}
		assertEquals(0, plain, "one ordinary blow never wears a fresh limb through");
		assertTrue(lethal > 150, "the killing blow mostly takes it: " + lethal);
		// a blunt weapon still never cuts
		assertEquals(-1, WoundEngine.apply(new WoundState(profile), hit, WeaponKind.BLUNT, 20, true, rules, random).severedAt());
	}

	@Test
	void heavyBlowsSeverSooner() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		rules.setSeverChance(1);
		int arm = profile.part("right_arm").getIndex();
		Hit hit = new Hit(arm, 1, 9, 1, new float[3], 0);
		// lower arm: durability 8. A 7 damage blow wears 7 * 1.45 > 8 at once
		assertEquals(7, WoundEngine.apply(new WoundState(profile), hit, WeaponKind.SWORD, 7, rules, new Random(1)).severedAt());
		// a heavy chop at the head counts double
		int head = profile.part("head").getIndex();
		WoundState state = new WoundState(profile);
		Hit chop = new Hit(head, 3, 4, 0, new float[3], 0);
		assertEquals(-1, WoundEngine.apply(state, chop, WeaponKind.AXE, 9, rules, new Random(1)).severedAt());
		assertEquals(0, WoundEngine.apply(state, chop, WeaponKind.AXE, 9, rules, new Random(1)).severedAt());
	}

	@Test
	void blastTearsDeeperThanABlow() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundRules rules = new WoundRules();
		rules.setSevering(false);
		WoundState state = new WoundState(profile);
		var hits = HitLocator.blast(state, SOUTH, 0, 65, 2, 0.6, 1.95, 5, new Random(2));
		var outcome = WoundEngine.blast(state, hits, 10, rules, new Random(2));
		int deep = 0;
		for (int i = 0; i < state.partCount(); i++) {
			PartState part = state.part(i);
			var def = part.getPart();
			for (int x = 0; x < def.gx(); x++)
				for (int y = 0; y < def.gy(); y++)
					for (int z = 0; z < def.gz(); z++) if (part.carved(x, y, z) && z == 2 && def.gz() == 4) deep++;
		}
		assertTrue(outcome.carved() > 20, "a chunk, not a nick: " + outcome.carved());
		assertTrue(deep > 0, "it goes two voxels in");
	}

	@Test
	void handsGoWithTheirArms() {
		BodyProfile profile = PROFILES.byId("skeleton");
		WoundState state = new WoundState(profile);
		assertFalse(state.handless("right"));
		assertFalse(state.armless());
		state.part(profile.part("right_arm").getIndex()).sever(7);
		assertTrue(state.handless("right"));
		assertFalse(state.handless("left"));
		assertFalse(state.armless());
		state.part(profile.part("left_arm").getIndex()).sever(3);
		assertTrue(state.armless());
	}

	@Test
	void apiSeversAndWoundsDirectly() throws Exception {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundBook book = new WoundBook(PROFILES, new Random(1));
		UUID id = UUID.randomUUID();
		WoundState state = book.getOrBlank(id, profile);
		int leg = profile.part("left_leg").getIndex();
		var messages = book.sever(id, 5, state, leg, 5, null, false, 9);
		assertEquals(2, messages.size());
		assertEquals(5, book.get(id).part(leg).getSeveredAt());
		assertTrue(book.sever(id, 5, state, leg, 8, null, false, 9).isEmpty(), "below an existing cut nothing more comes off");
		var event = (Protocol.Message.Event) Protocol.readClientbound(messages.getFirst(), PROFILES);
		assertEquals(Protocol.EVENT_SEVER, event.kind());
		assertEquals(9, event.attacker());

		Hit hit = HitLocator.cast(state, SOUTH, 0, 65.0, 2, 0, 0, -1, 4);
		assertFalse(book.wound(id, 5, state, hit, null, 0, 1.5, 1, -1).isEmpty());
		assertTrue(book.get(id).part(hit.part()).carvedCount() > 0);
	}

	@Test
	void corpseRoundTrip() throws Exception {
		var message = Protocol.readClientbound(Protocol.corpse(42, 0.6F, -0.8F, 0.7F, 1234, 400), PROFILES);
		var corpse = assertInstanceOf(Protocol.Message.Corpse.class, message);
		assertEquals(42, corpse.entityId());
		assertEquals(-0.8F, corpse.dz());
		assertEquals(1234, corpse.seed());
		assertEquals(400, corpse.ticks());
	}

	@Test
	void apiCutFollowsTheSwing() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundBook book = new WoundBook(PROFILES, new Random(1));
		UUID id = UUID.randomUUID();
		WoundState state = book.getOrBlank(id, profile);
		var spot = ru.deelter.chopchop.core.api.BodyOps.locate(state, SOUTH, 0, 64.9, 2, 0, 0, -1, 4, true);
		assertNotNull(spot);
		assertEquals("body", spot.part());
		assertTrue(spot.along() > 0 && spot.along() < 1);
		// an overhead chop: the cut runs up and down the body
		float[] axis = ru.deelter.chopchop.core.api.BodyOps.gridDirection(state, SOUTH, spot.internal().part(), 0, -1, 0);
		assertTrue(Math.abs(axis[1]) > 0.99, "vertical swing, vertical cut");
		ru.deelter.chopchop.core.api.BodyOps.wound(book, id, 1, state, SOUTH, spot, new double[]{0, -1, 0}, 8, 0.8, 1, -1);
		PartState body = book.get(id).part(spot.internal().part());
		int minY = 99, maxY = -1, minX = 99, maxX = -1;
		for (int x = 0; x < 8; x++) for (int y = 0; y < 12; y++) for (int z = 0; z < 4; z++) {
			if (!body.carved(x, y, z)) continue;
			minY = Math.min(minY, y); maxY = Math.max(maxY, y); minX = Math.min(minX, x); maxX = Math.max(maxX, x);
		}
		assertTrue(maxY - minY > maxX - minX, "taller than wide");
		// sever by name and place: the left arm from its middle
		assertFalse(ru.deelter.chopchop.core.api.BodyOps.sever(book, id, 1, state, "left_arm", 0.5, false, -1).isEmpty());
		assertEquals(6, book.get(id).part(profile.part("left_arm").getIndex()).getSeveredAt());
		assertTrue(ru.deelter.chopchop.core.api.BodyOps.sever(book, id, 1, state, "tail", 0.5, false, -1).isEmpty());
	}

	@Test
	void clientAimIsTakenOnlyWhenPlausible() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundState state = new WoundState(profile);
		int arm = profile.part("right_arm").getIndex();
		// the zombie arm reaches forward; the attacker in front looks at its rest spot, the client saw it a bit lower
		var center = HitLocator.toWorld(SOUTH, HitLocator.voxelCenter(profile, profile.part(arm), 1, 8, 1).x,
				HitLocator.voxelCenter(profile, profile.part(arm), 1, 8, 1).y, HitLocator.voxelCenter(profile, profile.part(arm), 1, 8, 1).z);
		double ex = center.x, ey = center.y + 0.2, ez = center.z + 2.5;
		Vector3d look = new Vector3d(center.x - ex, center.y - ey, center.z - ez).normalize();
		var aim = Protocol.readAim(Protocol.aim(7, arm, 1, 8, 1, new float[]{0, 0, 1}, null));
		assertNotNull(aim);
		Hit hit = AimCheck.accept(state, SOUTH, aim, ex, ey, ez, look.x, look.y, look.z, 3);
		assertNotNull(hit);
		assertEquals(arm, hit.part());
		// the way in agrees with the look; a claim pointing the other way (to open the far side) is replaced
		float[] way = ru.deelter.chopchop.core.api.BodyOps.gridDirection(state, SOUTH, arm, look.x, look.y, look.z);
		assertTrue(hit.inward()[0] * way[0] + hit.inward()[1] * way[1] + hit.inward()[2] * way[2] > 0.99);
		var backwards = Protocol.readAim(Protocol.aim(7, arm, 1, 8, 1, new float[]{-way[0], -way[1], -way[2]}, null));
		Hit replaced = AimCheck.accept(state, SOUTH, backwards, ex, ey, ez, look.x, look.y, look.z, 3);
		assertTrue(replaced.inward()[0] * way[0] + replaced.inward()[1] * way[1] + replaced.inward()[2] * way[2] > 0.99);
		// looking far away from it: refused
		assertNull(AimCheck.accept(state, SOUTH, aim, ex, ey, ez, 1, 0, 0, 3));
		// out of reach: refused
		assertNull(AimCheck.accept(state, SOUTH, aim, ex, ey, ez + 10, look.x, look.y, look.z, 3));
		// a voxel that is gone, or out of the grid: refused
		state.part(arm).sever(5);
		assertNull(AimCheck.accept(state, SOUTH, aim, ex, ey, ez, look.x, look.y, look.z, 3));
		assertNull(AimCheck.accept(state, SOUTH, Protocol.readAim(Protocol.aim(7, arm, 9, 1, 1, null, null)), ex, ey, ez, look.x, look.y, look.z, 3));
		// broken messages are nothing
		assertNull(Protocol.readAim(new byte[]{Protocol.C_AIM, 1, 2}));
		assertEquals(-1, Protocol.readServerbound(Protocol.aim(7, arm, 1, 8, 1, null, null)));
	}

	@Test
	void corpsesCanBeCut() throws Exception {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundBook book = new WoundBook(PROFILES, new Random(5));
		WoundState living = new WoundState(profile);
		living.part(profile.part("left_arm").getIndex()).sever(7);
		WoundState body = book.copyOf(living, profile);
		assertNotSame(living, body);
		assertEquals(7, body.part(profile.part("left_arm").getIndex()).getSeveredAt());
		WoundRules rules = new WoundRules();
		rules.setLethalSeverChance(0.95);
		int leg = profile.part("right_leg").getIndex();
		var cut = Protocol.readCut(Protocol.cut(-5, leg, 1, 8, 1, null, null));
		assertNotNull(cut);
		assertEquals(-5, cut.entityId());
		assertNull(Protocol.readAim(Protocol.cut(-5, leg, 1, 8, 1, null, null)), "a cut is no aim");
		Hit hit = AimCheck.corpse(body, cut);
		assertNotNull(hit);
		boolean off = false;
		for (int i = 0; i < 20 && !off; i++) off = body.part(leg).severed() || book.hitCorpse(-5, body, hit, WeaponKind.AXE, 7, rules).size() == 2
				&& body.part(leg).severed();
		assertTrue(body.part(leg).severed(), "blows at a body take its limbs");
		assertFalse(living.part(leg).severed(), "the living state is not touched");
		assertNull(AimCheck.corpse(body, Protocol.readCut(Protocol.cut(-5, leg, 1, 10, 1, null, null))), "a cut off spot is gone");
		var message = (Protocol.Message.Corpse) Protocol.readClientbound(Protocol.corpse(3, 1, 0, 0.5F, 1, 100, -9), PROFILES);
		assertEquals(-9, message.corpseId());
	}

	@Test
	void horsesHaveBodies() {
		BodyProfile horse = PROFILES.forEntity("minecraft:horse");
		assertNotNull(horse);
		assertEquals("bone", PROFILES.forEntity("minecraft:skeleton_horse").getMaterial());
		WoundState state = new WoundState(horse);
		// a blow from the side at the middle of the body finds it, one at the front leg finds the leg
		BodyPose east = new BodyPose(0, 64, 0, 0, 1, 0);
		Hit side = HitLocator.cast(state, east, 3, 65.0, 0.1, -1, 0, 0, 6);
		assertNotNull(side);
		assertEquals("body", horse.part(side.part()).getName());
		Hit leg = HitLocator.castOrNearest(state, east, 3, 64.3, 0.6, -1, 0, 0, 6);
		assertNotNull(leg);
		assertTrue(horse.part(leg.part()).isLeg(), horse.part(leg.part()).getName());
		// heads come off, legs too
		WoundRules rules = new WoundRules();
		rules.setWoundChance(0);
		int head = horse.part("head").getIndex();
		for (int i = 0; i < 3; i++) WoundEngine.apply(state, new Hit(head, 2, 2, 2, new float[3], 0), WeaponKind.AXE, 5, rules, new Random(1));
		assertTrue(state.headless());
	}

	@Test
	void humanoidsHaveASkeletonInside() {
		var head = PROFILES.byId("zombie").part("head");
		var skull = head.getSkeleton();
		assertNotNull(skull);
		assertTrue(skull.holds(0, -4, 0), "the middle of the head is skull");
		assertFalse(skull.holds(-3.5F, -4, 0), "its shell is meat");
		// the face of the skull: the front of the head texture, 8 + x across, 8 + y down
		assertArrayEquals(new int[]{8 + 4, 8 + 4}, skull.texel(0.5F, -3.5F, -2.5F, new int[]{0, 0, -1}));
		assertNull(PROFILES.byId("skeleton").part("head").getSkeleton(), "a skeleton is bone already");
		int patches = 0;
		for (int i = 0; i < 1000; i++) if (skull.patch(i % 10, i / 10 % 10, i / 100)) patches++;
		assertTrue(patches > 150 && patches < 350, "about a quarter of the skull shows meat: " + patches);
	}

	@Test
	void bareHandedMobsTearAtTheWholeBody() {
		BodyProfile profile = PROFILES.byId("player");
		WoundState state = new WoundState(profile);
		Random random = new Random(11);
		java.util.Map<String, Integer> parts = new java.util.HashMap<>();
		// a zombie in front of the player, its eyes at the height of the player's
		double ex = 0, ey = 65.62, ez = 1.2;
		for (int i = 0; i < 400; i++) {
			double[] at = ru.deelter.chopchop.core.hit.MobAim.spread(random, 0.6, 1.8);
			double dx = at[0] - ex, dy = 64 + at[1] - ey, dz = at[2] - ez;
			double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
			Hit hit = HitLocator.castOrNearest(state, SOUTH, ex, ey, ez, dx / l, dy / l, dz / l, 3);
			parts.merge(profile.part(hit.part()).getName(), 1, Integer::sum);
		}
		int head = parts.getOrDefault("head", 0);
		assertTrue(head < 120, "the head is not every bite: " + parts);
		assertTrue(parts.getOrDefault("body", 0) > 60, "the chest takes most: " + parts);
		assertTrue(parts.getOrDefault("right_arm", 0) + parts.getOrDefault("left_arm", 0) > 20, "arms too: " + parts);
		assertTrue(parts.getOrDefault("right_leg", 0) + parts.getOrDefault("left_leg", 0) > 40, "legs too: " + parts);
	}

	@Test
	void corpsesCanBeCutByTheApi() {
		BodyProfile profile = PROFILES.byId("zombie");
		WoundState body = new WoundState(profile);
		int arm = profile.part("right_arm").getIndex();
		assertEquals(2, WoundBook.severCorpse(-3, body, arm, 6, false).size());
		assertEquals(6, body.part(arm).getSeveredAt());
		assertTrue(WoundBook.severCorpse(-3, body, arm, 9, false).isEmpty());
		int chest = profile.part("body").getIndex();
		assertEquals(2, WoundBook.woundCorpse(-3, body, chest, 5, 3, 1.2, 1).size());
		assertTrue(body.part(chest).carvedCount() > 0);
		for (int x = 0; x < 8; x++) for (int y = 0; y < 12; y++) assertFalse(body.part(chest).carved(x, y, 3), "never the back");
	}

	@Test
	void bonesSitInTheMiddleOfTheirParts() {
		for (String id : new String[]{"player", "zombie", "humanoid"}) {
			BodyProfile profile = PROFILES.byId(id);
			for (int i = 0; i < profile.getParts().size(); i++) {
				var part = profile.part(i);
				if (part.getSkeleton() == null) continue;
				float[] b = part.getBox(), k = part.getSkeleton().box();
				assertEquals((b[0] + b[3]) / 2, (k[0] + k[3]) / 2, 0.01, id + " " + part.getName() + " across");
				assertEquals((b[2] + b[5]) / 2, (k[2] + k[5]) / 2, 0.01, id + " " + part.getName() + " front to back");
			}
		}
	}
}
