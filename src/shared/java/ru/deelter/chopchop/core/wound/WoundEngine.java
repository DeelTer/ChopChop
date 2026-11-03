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
				carved = carve(part, hit.x(), hit.y(), hit.z(), hit.slash(), length / 2, rules.getSlashWidth(), rules);
			} else {
				double radius = Math.min(rules.getWoundRadiusMax(),
						rules.getWoundRadiusBase() + rules.getWoundRadiusPerDamage() * damage) * weaponRule.woundSize();
				carved = carve(part, hit.x(), hit.y(), hit.z(), null, 0, radius, rules);
			}
		}

		if (rules.isSevering() && def.severable() && weaponRule.sever() > 0
				&& (rules.isHeads() || !"head".equals(def.getName())) && !lastLegProtected(state, part, rules)) {
			int segment = def.segmentAt(hit.y());
			if (segment >= 0) {
				BodyPart.Segment seg = def.getSegments().get(segment);
				boolean off;
				if (seg.getHits() > 0) {
					// counted chops: sure and the same every time, made for a head
					// a body lying without legs loses its head quicker
					int needed = state.legless() && rules.getLeglessHeadHits() > 0 ? Math.min(seg.getHits(), rules.getLeglessHeadHits()) : seg.getHits();
					off = part.addSegmentHit(segment) >= needed;
				} else {
					float total = part.addSegmentDamage(segment, (float) (damage * weaponRule.sever()));
					off = damage >= rules.getMinSeverDamage() && total >= seg.getDurability() * rules.getDurabilityMultiplier()
							&& random.nextDouble() < rules.getSeverChance();
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
	 * Removes the voxels within {@code radius} of a line through the hit voxel ({@code axis} times
	 * {@code halfLength} both ways; a point when the axis is null), nearest first, within the depth and share limits.
	 * The hit voxel always goes first, so a second blow at the same spot digs deeper.
	 *
	 * @return how many voxels went
	 */
	static int carve(PartState part, int cx, int cy, int cz, float[] axis, double halfLength, double radius, WoundRules rules) {
		BodyPart def = part.getPart();
		int limit = (int) Math.floor(def.volume() * rules.getMaxCarvedShare()) - part.carvedCount();
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
					if (depth > rules.getMaxWoundDepth() + 1 || !center && depth > rules.getMaxWoundDepth()) continue;
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
		double radius = Math.min(rules.getWoundRadiusMax() * 1.2, rules.getWoundRadiusBase() + damage * 0.1);
		java.util.Set<Integer> exposed = new java.util.LinkedHashSet<>();
		for (Hit hit : hits) {
			PartState part = state.part(hit.part());
			exposed.add(hit.part());
			if (part.getPart().isWoundable()) carved += carve(part, hit.x(), hit.y(), hit.z(), null, 0, radius, rules);
		}
		double over = damage - rules.getExplosionSeverDamage();
		if (rules.isSevering() && over > 0) {
			double chance = Math.min(0.9, over * rules.getExplosionSeverChancePerDamage());
			for (int index : exposed) {
				PartState part = state.part(index);
				BodyPart def = part.getPart();
				if (!def.severable() || lastLegProtected(state, part, rules)) continue;
				boolean head = "head".equals(def.getName());
				if (head && (!rules.isHeads() || over < rules.getExplosionSeverDamage() * 1.5)) continue;
				if (random.nextDouble() >= (head ? chance / 2 : chance)) continue;
				// a close blast takes the whole limb, a far one its end
				var segments = def.getSegments();
				int cut = over > rules.getExplosionSeverDamage() ? segments.getFirst().getCut() : segments.getLast().getCut();
				if (part.sever(cut)) severed.add(new int[]{index, cut});
			}
		}
		var head = state.getProfile().part("head");
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
