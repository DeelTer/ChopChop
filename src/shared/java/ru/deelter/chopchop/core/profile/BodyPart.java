package ru.deelter.chopchop.core.profile;

import lombok.Getter;

import java.util.List;

/**
 * One part of a body, as a model part of the game draws it. Read from a profile file.
 * <p>
 * Coordinates are model units (pixels), in the space of the model the way the game builds it: y points down, the
 * feet are at y = 24. {@link #pivot} and {@link #rot} are the rest pose of the part; {@link #box} is its main
 * cube in part space. The wound grid divides the box into voxels of about one pixel.
 */
@Getter
public final class BodyPart {
	/**
	 * name of the model part, "right_arm"
	 */
	private String name;
	/**
	 * name of the part this one hangs on, null for the root
	 */
	private String parent;
	private float[] pivot = {0, 0, 0};
	/**
	 * rest rotation in radians, applied z, y, x like ModelPart
	 */
	private float[] rot = {0, 0, 0};
	/**
	 * minX, minY, minZ, maxX, maxY, maxZ
	 */
	private float[] box;
	/**
	 * voxels along x, y, z. Missing: one per pixel of the box
	 */
	private int[] grid;
	/**
	 * model parts drawn over this one that follow its wounds: sleeves, jacket, hat
	 */
	private List<String> overlays = List.of();
	/**
	 * "right" or "left" for the arm with a hand, null otherwise
	 */
	private String hand;
	private boolean woundable = true;
	/**
	 * the body stands on it: with all of them cut off, the body crawls (see {@link BodyProfile#getCrawl})
	 */
	private boolean leg;
	/**
	 * Where the part comes off, from the root of the limb to its end. Empty: it never does
	 */
	private List<Segment> segments = List.of();
	/**
	 * size of the bone at a stump, 1 is the bone of the model as drawn in Blockbench
	 */
	private float boneScale = 0.35F;
	/**
	 * material of the inside, overrides the one of the profile
	 */
	private String material;

	/**
	 * Index in the profile, set when it is loaded.
	 */
	private transient int index;

	public int gx() {
		return grid[0];
	}

	public int gy() {
		return grid[1];
	}

	public int gz() {
		return grid[2];
	}

	public int volume() {
		return grid[0] * grid[1] * grid[2];
	}

	public boolean severable() {
		return !segments.isEmpty();
	}

	/**
	 * The segment a row of voxels belongs to, -1 above the first cut.
	 */
	public int segmentAt(int y) {
		int found = -1;
		for (int i = 0; i < segments.size(); i++) {
			if (y >= segments.get(i).getCut()) found = i;
		}
		return found;
	}

	void init(int index, String profileMaterial) {
		this.index = index;
		if (name == null || box == null || box.length != 6) throw new IllegalArgumentException("part without name or box");
		if (grid == null) {
			grid = new int[]{
					Math.max(1, Math.round(box[3] - box[0])),
					Math.max(1, Math.round(box[4] - box[1])),
					Math.max(1, Math.round(box[5] - box[2]))};
		}
		if (grid.length != 3 || volume() > PartLimits.MAX_VOXELS || gx() < 1 || gy() < 1 || gz() < 1) {
			throw new IllegalArgumentException("bad grid of part " + name);
		}
		if (material == null) material = profileMaterial;
		if (overlays == null) overlays = List.of();
		if (segments == null) segments = List.of();
		// a cut at row 0 takes the whole part: a head off at the neck
		int last = -1;
		for (Segment segment : segments) {
			if (segment.getCut() <= last || segment.getCut() >= gy()) {
				throw new IllegalArgumentException("segments of " + name + " must cut inside the part, root first");
			}
			last = segment.getCut();
		}
	}

	@Getter
	public static final class Segment {
		private String name;
		/**
		 * first row of voxels (y) that comes off with this segment
		 */
		private int cut;
		/**
		 * damage the segment takes before it can come off
		 */
		private float durability = 8;
		/**
		 * When above 0: the segment comes off on this many hits of a weapon that severs, whatever their damage and
		 * without chance. For heads: three chops and it flies
		 */
		private int hits;
	}
}
