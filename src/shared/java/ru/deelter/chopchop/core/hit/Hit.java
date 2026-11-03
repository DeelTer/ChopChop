package ru.deelter.chopchop.core.hit;

/**
 * Where a hit lands: the first voxel of a part that is still there along the ray.
 *
 * @param part  index of the part in its profile
 * @param x     voxel of the part grid
 * @param model the entry point in model space (pixels), for effects
 * @param depth distance along the ray in model space, to pick the nearest part
 * @param slash  direction of a cut across the surface, in voxels of the grid (unit length): level in the world and
 *               across the blow, as a swing draws it. Null: no direction known, the wound is round
 * @param inward direction the blow went into the part, in voxels of the grid (unit length). A wound only digs in
 *               from the side it was struck on and never comes out on the far side. Null: not known
 */
public record Hit(int part, int x, int y, int z, float[] model, double depth, float[] slash, float[] inward) {
	public Hit(int part, int x, int y, int z, float[] model, double depth) {
		this(part, x, y, z, model, depth, null, null);
	}

	public Hit(int part, int x, int y, int z, float[] model, double depth, float[] slash) {
		this(part, x, y, z, model, depth, slash, null);
	}
}
