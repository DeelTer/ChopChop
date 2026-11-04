package ru.deelter.chopchop.core.wound;

import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.rules.WeaponKind;
import ru.deelter.chopchop.core.rules.WoundRules;

import java.util.random.RandomGenerator;

/**
 * Turns a hit that the server accepted into wounds. Pure rules, no game classes: both backends call it the same way.
 */
public final class WoundEngine {
	private WoundEngine() {
	}

	/**
	 * @param damage health the target actually lost, after armor
	 */
	public static Outcome apply(WoundState state, Hit hit, WeaponKind weapon, float damage, WoundRules rules, RandomGenerator random) {
		return apply(state, hit, weapon, damage, false, rules, random);
	}

	/**
	 * @param damage health the target lost; for the blow that killed it, the damage of the blow
	 * @param lethal this blow killed the body: a sword or an axe likely takes what it hits
	 */
	public static Outcome apply(WoundState state, Hit hit, WeaponKind weapon, float damage, boolean lethal, WoundRules rules,
								RandomGenerator random) {
		return apply(state, hit, weapon, damage, lethal, 0, rules, random);
	}

	/**
	 * @param sharpness level of Sharpness on the blade: limbs wear through faster and come off more often, and every
	 *                  {@code sharpnessHeadLevels} levels a chop at a head counts one more
	 */
	public static Outcome apply(WoundState state, Hit hit, WeaponKind weapon, float damage, boolean lethal, int sharpness,
								WoundRules rules, RandomGenerator random) {
		double sharp = weapon.edged() ? Math.max(0, sharpness) * rules.getSharpnessSeverBonus() : 0;
		PartState part = state.part(hit.part());
		BodyPart def = part.getPart();
		WoundRules.WeaponRule weaponRule = rules.weapon(weapon);
		int carved = 0;
		int severedAt = -1;

		if (def.isWoundable() && random.nextDouble() < rules.getWoundChance() * weaponRule.woundChance()) {
			if (weapon.edged() && hit.slash() != null) {
				// a blade leaves a scar across the body, the way it swung
				double length = Math.min(rules.getSlashLengthMax(),
						rules.getSlashLengthBase() + rules.getSlashLengthPerDamage() * damage) * weaponRule.woundSize();
				carved = carve(part, hit, hit.slash(), length / 2, rules.getSlashWidth(), depthOf(weapon, rules), rules.getMaxCarvedShare());
			} else {
				double radius = Math.min(rules.getWoundRadiusMax(),
						rules.getWoundRadiusBase() + rules.getWoundRadiusPerDamage() * damage) * weaponRule.woundSize();
				carved = carve(part, hit, null, 0, radius, depthOf(weapon, rules), rules.getMaxCarvedShare());
			}
		}

		if (rules.isSevering() && def.severable() && weaponRule.sever() > 0
				&& (rules.isHeads() || !def.isHead()) && !lastLegProtected(state, part, rules)) {
			int segment = def.segmentAt(hit.y());
			if (segment >= 0) {
				BodyPart.Segment seg = def.getSegments().get(segment);
				boolean off;
				double over = Math.max(0, damage - rules.getMinSeverDamage());
				if (seg.getHits() > 0) {
					// counted chops: sure and the same every time, made for a head; a heavy blow counts for more
					// a body lying without legs loses its head quicker
					int needed = state.legless() && rules.getLeglessHeadHits() > 0 ? Math.min(seg.getHits(), rules.getLeglessHeadHits()) : seg.getHits();
					int count = rules.getHeadHitDamage() > 0 ? 1 + (int) (damage / rules.getHeadHitDamage()) : 1;
					if (weapon.edged() && rules.getSharpnessHeadLevels() > 0) count += Math.max(0, sharpness) / rules.getSharpnessHeadLevels();
					int total = 0;
					for (int i = 0; i < count; i++) total = part.addSegmentHit(segment);
					off = total >= needed || lethal && random.nextDouble() < rules.getLethalHeadChance();
				} else {
					float worn = (float) (damage * weaponRule.sever() * (1 + over * rules.getSeverHeavyBonus()) * (1 + sharp));
					float total = part.addSegmentDamage(segment, worn);
					double chance = Math.min(0.95, (rules.getSeverChance() + over * rules.getSeverChancePerDamage()) * (1 + sharp));
					off = damage >= rules.getMinSeverDamage() && total >= seg.getDurability() * rules.getDurabilityMultiplier()
							&& random.nextDouble() < chance;
					// the killing blow does not wait for the limb to wear through
					if (!off && lethal) off = random.nextDouble() < Math.min(0.95, rules.getLethalSeverChance() * Math.min(1.5, weaponRule.sever()) * (1 + sharp));
				}
				if (off && part.sever(seg.getCut())) severedAt = seg.getCut();
			}
		}

		if (carved > 0 || severedAt >= 0) state.touch();
		return new Outcome(hit.part(), carved, severedAt);
	}

	/**
	 * With {@code legless: protect}, the one leg still on stays on.
	 */
	static boolean lastLegProtected(WoundState state, PartState part, WoundRules rules) {
		if (!rules.protectsLastLeg() || !part.getPart().isLeg() || part.severed()) return false;
		for (int i = 0; i < state.partCount(); i++) {
			PartState other = state.part(i);
			if (other != part && other.getPart().isLeg() && !other.severed()) return false;
		}
		return true;
	}

	/**
	 * How deep a weapon may dig, voxels under the original surface.
	 */
	static int depthOf(WeaponKind weapon, WoundRules rules) {
		return rules.getMaxWoundDepth() + (weapon == WeaponKind.AXE ? Math.max(0, rules.getAxeExtraDepth()) : 0);
	}

	/**
	 * Carves a wound at a hit directly, without chance or weapon: for the API. Keeps the direction rules (never
	 * through to the far side).
	 */
	public static int carveAt(PartState part, Hit hit, float[] axis, double halfLength, double radius, int maxDepth, double share) {
		return carve(part, hit, axis, halfLength, radius, Math.max(0, maxDepth), share);
	}

	static int carve(PartState part, Hit hit, float[] axis, double halfLength, double radius, int maxDepth, double share) {
		return carve(part, hit.x(), hit.y(), hit.z(), hit.inward(), axis, halfLength, radius, maxDepth, share);
	}

	/**
	 * Removes the voxels within {@code radius} of a line through the hit voxel ({@code axis} times
	 * {@code halfLength} both ways; a point when the axis is null), nearest first, within the depth and share limits.
	 * The hit voxel always goes first, so a second blow at the same spot digs deeper.
	 * <p>
	 * With the direction of the blow known, depth is also counted from the face it struck: parts are only four voxels
	 * thick, and a wound must never come out on the far side, a blow to the chest does not open the back.
	 *
	 * @param inward direction of the blow in voxels of the grid, null if unknown
	 * @return how many voxels went
	 */
	static int carve(PartState part, int cx, int cy, int cz, float[] inward, float[] axis, double halfLength, double radius, int maxDepth,
					 double share) {
		BodyPart def = part.getPart();
		int limit = (int) Math.floor(def.volume() * share) - part.carvedCount();
		if (limit <= 0) return 0;
		double ax = axis == null ? 0 : axis[0], ay = axis == null ? 0 : axis[1], az = axis == null ? 0 : axis[2];
		double half = axis == null ? 0 : halfLength;
		int reach = (int) Math.ceil(half + radius);
		java.util.List<double[]> candidates = new java.util.ArrayList<>();
		for (int x = cx - reach; x <= cx + reach; x++) {
			for (int y = cy - reach; y <= cy + reach; y++) {
				for (int z = cz - reach; z <= cz + reach; z++) {
					if (part.gone(x, y, z)) continue;
					double dx = x - cx, dy = y - cy, dz = z - cz;
					// distance to the segment of the cut
					double along = Math.max(-half, Math.min(half, dx * ax + dy * ay + dz * az));
					double px = dx - ax * along, py = dy - ay * along, pz = dz - az * along;
					double distance = Math.sqrt(px * px + py * py + pz * pz);
					boolean center = x == cx && y == cy && z == cz;
					if (!center && distance > radius) continue;
					int depth = depth(def, x, y, z);
					if (depth > maxDepth + 1 || !center && depth > maxDepth) continue;
					int entry = entryDepth(def, inward, x, y, z);
					if (entry > maxDepth + 1 || !center && entry > maxDepth) continue;
					candidates.add(new double[]{center ? -1 : distance + Math.abs(along) * 0.01, x, y, z});
				}
			}
		}
		candidates.sort(java.util.Comparator.comparingDouble(c -> c[0]));
		int count = 0;
		for (double[] c : candidates) {
			if (count >= limit) break;
			part.carve((int) c[1], (int) c[2], (int) c[3]);
			count++;
		}
		return count;
	}

	/**
	 * How far a voxel lies from the face a blow came in through, along the main axis of the blow; 0 without a
	 * direction. The far face of a part is as deep as the part is thick, out of reach of any wound.
	 */
	static int entryDepth(BodyPart def, float[] inward, int x, int y, int z) {
		if (inward == null) return 0;
		int axis = Math.abs(inward[0]) >= Math.abs(inward[1])
				? (Math.abs(inward[0]) >= Math.abs(inward[2]) ? 0 : 2)
				: (Math.abs(inward[1]) >= Math.abs(inward[2]) ? 1 : 2);
		int coordinate = axis == 0 ? x : axis == 1 ? y : z;
		int size = axis == 0 ? def.gx() : axis == 1 ? def.gy() : def.gz();
		int depth = inward[axis] >= 0 ? coordinate : size - 1 - coordinate;
		// the far face itself never goes, however deep the weapon digs
		return size > 1 && depth >= size - 1 ? Integer.MAX_VALUE : depth;
	}

	/**
	 * A blast tears the body at the spots its rays reach, and above {@code explosionSeverDamage} limbs facing it come
	 * off: the closer (the more damage), the more likely and the nearer to the body they come off. A head goes only
	 * to a very close blast, and only with heads on.
	 *
	 * @param hits where rays from the blast reach the body, one per spot
	 */
	public static BlastOutcome blast(WoundState state, java.util.List<Hit> hits, float damage, WoundRules rules, RandomGenerator random) {
		return blast(state, hits, damage, false, rules, random);
	}

	/**
	 * @param takeHead the head comes off whatever the damage: the blast killed a player
	 */
	public static BlastOutcome blast(WoundState state, java.util.List<Hit> hits, float damage, boolean takeHead, WoundRules rules,
									 RandomGenerator random) {
		int carved = 0;
		java.util.List<int[]> severed = new java.util.ArrayList<>();
		// a blast tears chunks out, bigger and deeper than a blow of the same damage
		double scale = Math.max(1, rules.getExplosionWoundScale());
		double radius = Math.min(rules.getWoundRadiusMax() * scale, (rules.getWoundRadiusBase() + damage * 0.12) * scale);
		int depth = Math.max(rules.getMaxWoundDepth(), rules.getExplosionWoundDepth());
		double share = Math.max(rules.getMaxCarvedShare(), rules.getExplosionCarvedShare());
		java.util.Set<Integer> exposed = new java.util.LinkedHashSet<>();
		for (Hit hit : hits) {
			PartState part = state.part(hit.part());
			exposed.add(hit.part());
			if (part.getPart().isWoundable()) carved += carve(part, hit, null, 0, radius, depth, share);
		}
		double over = damage - rules.getExplosionSeverDamage();
		if (rules.isSevering() && over > 0) {
			double chance = Math.min(0.9, over * rules.getExplosionSeverChancePerDamage());
			for (int index : exposed) {
				PartState part = state.part(index);
				BodyPart def = part.getPart();
				if (!def.severable() || lastLegProtected(state, part, rules)) continue;
				boolean head = def.isHead();
				if (head && (!rules.isHeads() || over < rules.getExplosionSeverDamage() * 1.5)) continue;
				if (random.nextDouble() >= (head ? chance / 2 : chance)) continue;
				// a close blast takes the whole limb, a far one its end
				var segments = def.getSegments();
				int cut = over > rules.getExplosionSeverDamage() ? segments.getFirst().getCut() : segments.getLast().getCut();
				if (part.sever(cut)) severed.add(new int[]{index, cut});
			}
		}
		var head = state.getProfile().head();
		if (takeHead && head != null && state.part(head.getIndex()).sever(0)) severed.add(new int[]{head.getIndex(), 0});
		if (carved > 0 || !severed.isEmpty()) state.touch();
		return new BlastOutcome(carved, severed);
	}

	/**
	 * @param severed part index and cut row of every part that came off
	 */
	public record BlastOutcome(int carved, java.util.List<int[]> severed) {
		public boolean changed() {
			return carved > 0 || !severed.isEmpty();
		}
	}

	/**
	 * How many voxels lie between this one and the outside of the original box, 0 on the surface. Rows of the
	 * long axis ends do not count as surface: an arm is not carved from the shoulder.
	 */
	static int depth(BodyPart def, int x, int y, int z) {
		int dx = Math.min(x, def.gx() - 1 - x);
		int dz = Math.min(z, def.gz() - 1 - z);
		int dy = Math.min(y, def.gy() - 1 - y);
		if (def.gy() > Math.max(def.gx(), def.gz())) return Math.min(dx, dz);
		return Math.min(dx, Math.min(dy, dz));
	}

	/**
	 * @param carved    voxels carved by this hit
	 * @param severedAt row the part was cut at by this hit, -1 if it was not
	 */
	public record Outcome(int part, int carved, int severedAt) {
		public boolean changed() {
			return carved > 0 || severedAt >= 0;
		}
	}
}
