package ru.deelter.chopchop.core.wound;

import lombok.Getter;
import ru.deelter.chopchop.core.profile.BodyPart;

import java.util.BitSet;

/**
 * Damage of one body part: which voxels of its grid are carved out, and from which row on it is cut off.
 * <p>
 * Voxel index: {@code x + gx * (y + gy * z)}, see {@link #index}. Cut off means every voxel with
 * {@code y >= severedAt}; the carved set keeps only wounds, so the shape of a limb before it came off is still known.
 */
public final class PartState {
	public static final int NOT_SEVERED = Integer.MAX_VALUE;

	@Getter
	private final BodyPart part;
	private final BitSet carved;
	@Getter
	private int severedAt = NOT_SEVERED;
	/**
	 * damage taken by each segment towards coming off. Server only, never sent
	 */
	private final float[] segmentDamage;
	/**
	 * edged hits each segment took, for segments that come off on a number of hits. Server only
	 */
	private final int[] segmentHits;

	public PartState(BodyPart part) {
		this.part = part;
		this.carved = new BitSet(part.volume());
		this.segmentDamage = new float[part.getSegments().size()];
		this.segmentHits = new int[part.getSegments().size()];
	}

	public PartState copy() {
		PartState copy = new PartState(part);
		copy.carved.or(carved);
		copy.severedAt = severedAt;
		System.arraycopy(segmentDamage, 0, copy.segmentDamage, 0, segmentDamage.length);
		System.arraycopy(segmentHits, 0, copy.segmentHits, 0, segmentHits.length);
		return copy;
	}

	public int index(int x, int y, int z) {
		return x + part.gx() * (y + part.gy() * z);
	}

	public boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < part.gx() && y < part.gy() && z < part.gz();
	}

	/**
	 * No longer there: carved out or cut off. Outside the grid counts as gone.
	 */
	public boolean gone(int x, int y, int z) {
		return !inside(x, y, z) || y >= severedAt || carved.get(index(x, y, z));
	}

	public boolean carved(int x, int y, int z) {
		return inside(x, y, z) && carved.get(index(x, y, z));
	}

	public boolean severed() {
		return severedAt != NOT_SEVERED;
	}

	public boolean pristine() {
		return carved.isEmpty() && !severed();
	}

	public int carvedCount() {
		return carved.cardinality();
	}

	public void carve(int x, int y, int z) {
		if (inside(x, y, z)) carved.set(index(x, y, z));
	}

	/**
	 * Cuts off from this row on. A cut further down than an existing one changes nothing: that part is gone already.
	 *
	 * @return whether more came off
	 */
	public boolean sever(int row) {
		if (row < 0 || row >= part.gy() || row >= severedAt) return false;
		severedAt = row;
		for (int i = 0; i < segmentDamage.length; i++) {
			if (part.getSegments().get(i).getCut() >= row) {
				segmentDamage[i] = 0;
				segmentHits[i] = 0;
			}
		}
		return true;
	}

	public float addSegmentDamage(int segment, float damage) {
		return segmentDamage[segment] += damage;
	}

	public int addSegmentHit(int segment) {
		return ++segmentHits[segment];
	}

	public BitSet carvedBits() {
		return (BitSet) carved.clone();
	}

	public void load(BitSet bits, int severedAt) {
		carved.clear();
		bits.stream().filter(i -> i < part.volume()).forEach(carved::set);
		this.severedAt = severedAt < 0 || severedAt >= part.gy() ? NOT_SEVERED : severedAt;
	}

	public void clear() {
		carved.clear();
		severedAt = NOT_SEVERED;
		java.util.Arrays.fill(segmentDamage, 0);
		java.util.Arrays.fill(segmentHits, 0);
	}
}
