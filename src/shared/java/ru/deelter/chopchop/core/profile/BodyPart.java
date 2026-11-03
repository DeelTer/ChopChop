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
	 * The bone of a vanilla skeleton inside the part: where it is and how the skeleton texture lies on it. A wound
	 * that reaches it shows the skull, the ribs and spine, the bones of a limb instead of meat. Null: meat only
	 */
	private Skeleton skeleton;

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

	/**
	 * @param box   the voxels that are bone, part space pixels: min x, y, z, max x, y, z
	 * @param cube  the cube of the skeleton model the texture is drawn on, part space pixels (it may be larger than
	 *              the bone, a skull lies inside a shell of meat)
	 * @param uv    where that cube's texture starts in the skeleton texture, pixels
	 * @param patchy share of the bone voxels that show meat anyway, picked by place, the same every time: a wound
	 *              shows the skull here and meat right next to it
	 */
	public record Skeleton(float[] box, float[] cube, int[] uv, float patchy) {
		public boolean holds(float px, float py, float pz) {
			return box != null && box.length == 6 && px >= box[0] && px <= box[3] && py >= box[1] && py <= box[4] && pz >= box[2] && pz <= box[5];
		}

		/**
		 * Whether a bone voxel shows meat all the same.
		 */
		public boolean patch(int x, int y, int z) {
			if (patchy <= 0) return false;
			int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
			h ^= h >>> 13;
			h *= 0x5bd1e995;
			h ^= h >>> 15;
			return (h & 0xFFFF) / 65536F < patchy;
		}

		/**
		 * The texel of the skeleton texture a face of a bone voxel shows: the face of the cube with the same normal,
		 * where the voxel's center lies on it. Minecraft's unwrapping of a cube.
		 *
		 * @param n the normal of the face, one axis
		 * @return u, v in pixels of the texture
		 */
		public int[] texel(float px, float py, float pz, int[] n) {
			int w = Math.max(1, Math.round(cube[3] - cube[0])), h = Math.max(1, Math.round(cube[4] - cube[1])), d = Math.max(1, Math.round(cube[5] - cube[2]));
			int ix = Math.clamp((int) Math.floor(px - cube[0]), 0, w - 1);
			int iy = Math.clamp((int) Math.floor(py - cube[1]), 0, h - 1);
			int iz = Math.clamp((int) Math.floor(pz - cube[2]), 0, d - 1);
			int u = uv[0], v = uv[1];
			if (n[2] < 0) return new int[]{u + d + ix, v + d + iy};
			if (n[2] > 0) return new int[]{u + d + w + d + (w - 1 - ix), v + d + iy};
			if (n[0] > 0) return new int[]{u + iz, v + d + iy};
			if (n[0] < 0) return new int[]{u + d + w + (d - 1 - iz), v + d + iy};
			if (n[1] < 0) return new int[]{u + d + ix, v + iz};
			return new int[]{u + d + w + ix, v + iz};
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
