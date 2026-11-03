package ru.deelter.chopchop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.Material;

import java.util.List;

/**
 * Builds the geometry of a damaged part from its cube and a mask, in the part's own space, so it follows every
 * animation of the part:
 * <ul>
 *     <li>skin: each face of the cube cut into one cell per texel, a cell is left out when the voxel under it is
 *     gone. The skin really has holes, nothing is pasted over it;</li>
 *     <li>inside: a face of meat (or bone) wherever a voxel that is there borders one that is gone, the walls and
 *     the bottom of every wound and the cap of a stump;</li>
 *     <li>a bone sticking out of a cut.</li>
 * </ul>
 * Model space is in pixels, {@code ModelPart.Vertex} keeps them that way; the pose takes blocks, hence the /16.
 */
public final class VoxelMesher {
	private static final int MAX_CELLS = 32;
	/**
	 * how far a skin cell looks inwards for its voxel, pixels: past the 0.25 of a sleeve, not past one voxel
	 */
	private static final float INSET = 0.3F;
	/**
	 * the inside of a skeleton: the shaft of the bone texture
	 */
	private static final float[] BONE_INSIDE_UV = {0F, 0.25F, 0.25F, 0.75F};
	private static final float[] MEAT_UV = {0F, 0F, 1F, 1F};
	private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private VoxelMesher() {
	}

	/**
	 * The frame of the voxel grid on a cube: where voxel 0 starts and how big a voxel is, in pixels.
	 */
	public record Grid(float ox, float oy, float oz, float sx, float sy, float sz, int gx, int gy, int gz) {
		static Grid of(ModelPart.Cube cube, Bindings.Binding binding, BodyPart part) {
			float[] b = part.getBox();
			boolean own = binding.single() && cube.maxX > cube.minX && cube.maxY > cube.minY && cube.maxZ > cube.minZ;
			float minX = own ? cube.minX : b[0], minY = own ? cube.minY : b[1], minZ = own ? cube.minZ : b[2];
			float maxX = own ? cube.maxX : b[3], maxY = own ? cube.maxY : b[4], maxZ = own ? cube.maxZ : b[5];
			return new Grid(minX, minY, minZ,
					(maxX - minX) / part.gx(), (maxY - minY) / part.gy(), (maxZ - minZ) / part.gz(),
					part.gx(), part.gy(), part.gz());
		}

		int cell(float p, float origin, float size, int count) {
			int i = (int) Math.floor((p - origin) / size);
			return Math.max(0, Math.min(count - 1, i));
		}
	}

	/**
	 * The skin of a cube with holes where {@code mask} says.
	 */
	public static void skin(ModelPart.Cube cube, Grid grid, VoxelMask mask, PoseStack.Pose pose, Sink sink) {
		Matrix4f matrix = pose.pose();
		Vector3f normal = new Vector3f();
		Vector3f p = new Vector3f();
		for (ModelPart.Polygon polygon : cube.polygons) {
			ModelPart.Vertex[] v = polygon.vertices();
			if (v.length != 4) continue;
			pose.transformNormal(polygon.normal(), normal);
			float ax = v[1].x() - v[0].x(), ay = v[1].y() - v[0].y(), az = v[1].z() - v[0].z();
			float bx = v[3].x() - v[0].x(), by = v[3].y() - v[0].y(), bz = v[3].z() - v[0].z();
			float au = v[1].u() - v[0].u(), av = v[1].v() - v[0].v();
			float bu = v[3].u() - v[0].u(), bv = v[3].v() - v[0].v();
			int na = Math.clamp(Math.round((float) Math.sqrt(ax * ax + ay * ay + az * az)), 1, MAX_CELLS);
			int nb = Math.clamp(Math.round((float) Math.sqrt(bx * bx + by * by + bz * bz)), 1, MAX_CELLS);
			float inX = -polygon.normal().x() * INSET, inY = -polygon.normal().y() * INSET, inZ = -polygon.normal().z() * INSET;
			for (int i = 0; i < na; i++) {
				for (int j = 0; j < nb; j++) {
					float cs = (i + 0.5F) / na, ct = (j + 0.5F) / nb;
					float cx = v[0].x() + ax * cs + bx * ct + inX;
					float cy = v[0].y() + ay * cs + by * ct + inY;
					float cz = v[0].z() + az * cs + bz * ct + inZ;
					if (mask.gone(grid.cell(cx, grid.ox, grid.sx, grid.gx), grid.cell(cy, grid.oy, grid.sy, grid.gy),
							grid.cell(cz, grid.oz, grid.sz, grid.gz))) continue;
					for (int k = 0; k < 4; k++) {
						float s = (k == 1 || k == 2 ? i + 1F : i) / na;
						float t = (k >= 2 ? j + 1F : j) / nb;
						float x = v[0].x() + ax * s + bx * t, y = v[0].y() + ay * s + by * t, z = v[0].z() + az * s + bz * t;
						matrix.transformPosition(x / 16F, y / 16F, z / 16F, p);
						sink.vertex(p.x, p.y, p.z, v[0].u() + au * s + bu * t, v[0].v() + av * s + bv * t, normal.x, normal.y, normal.z);
					}
				}
			}
		}
	}

	/**
	 * The faces of meat between voxels that are there and voxels that are gone, inside the grid only.
	 */
	public static void inside(Grid grid, VoxelMask mask, String material, PoseStack.Pose pose, Sink sink) {
		inside(grid, mask, material, pose, sink, null, null, null);
	}

	/**
	 * The same with a skeleton inside: a face of a voxel that is bone shows the texel of the bone's texture there,
	 * into {@code bones}; meat where the texture is see-through (between the ribs) or the bone is patchy.
	 *
	 * @param skeleton the bone of the part, null for none
	 */
	public static void inside(Grid grid, VoxelMask mask, String material, PoseStack.Pose pose, Sink sink,
							  ru.deelter.chopchop.core.profile.BodyPart.Skeleton skeleton, SkeletonTexture texture, Sink bones) {
		boolean skeletal = skeleton != null && texture != null && bones != null && skeleton.cube() != null && skeleton.uv() != null;
		float[] uv = Material.bleeds(material) ? MEAT_UV : BONE_INSIDE_UV;
		boolean meat = Material.bleeds(material);
		Matrix4f matrix = pose.pose();
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (int x = 0; x < grid.gx; x++) {
			for (int y = 0; y < grid.gy; y++) {
				for (int z = 0; z < grid.gz; z++) {
					if (mask.gone(x, y, z)) continue;
					for (int[] d : DIRS) {
						int nx = x + d[0], ny = y + d[1], nz = z + d[2];
						if (nx < 0 || ny < 0 || nz < 0 || nx >= grid.gx || ny >= grid.gy || nz >= grid.gz) continue;
						if (!mask.gone(nx, ny, nz)) continue;
						pose.transformNormal(d[0], d[1], d[2], n);
						if (skeletal && bone(grid, x, y, z, d, skeleton, texture, matrix, p, n, bones)) continue;
						face(grid, x, y, z, d, uv, matrix, p, n, sink, meat ? SHADES[Math.min(SHADES.length - 1, depth(grid, x, y, z))] : 1F);
					}
				}
			}
		}
	}

	/**
	 * Meat is darker the deeper in the body it is: the surface as it is, then two darker shades, never more.
	 */
	private static final float[] SHADES = {1F, 0.8F, 0.64F};

	/**
	 * How many voxels lie between this one and the outside of the grid. The ends of a long part (shoulder, wrist)
	 * do not count, a stump is cut across them.
	 */
	static int depth(Grid g, int x, int y, int z) {
		int d = Math.min(Math.min(x, g.gx - 1 - x), Math.min(z, g.gz - 1 - z));
		if (g.gy > Math.max(g.gx, g.gz)) return d;
		return Math.min(d, Math.min(y, g.gy - 1 - y));
	}

	private static void face(Grid g, int x, int y, int z, int[] d, float[] uv, Matrix4f matrix, Vector3f p, Vector3f n, Sink sink) {
		face(g, x, y, z, d, uv, matrix, p, n, sink, 1F);
	}

	/**
	 * A face of a bone voxel, drawn with its texel of the skeleton texture.
	 *
	 * @return false when the voxel is not bone or its texel is see-through: then it is meat
	 */
	private static boolean bone(Grid g, int x, int y, int z, int[] d, ru.deelter.chopchop.core.profile.BodyPart.Skeleton skeleton,
								SkeletonTexture texture, Matrix4f matrix, Vector3f p, Vector3f n, Sink bones) {
		float px = g.ox + (x + 0.5F) * g.sx, py = g.oy + (y + 0.5F) * g.sy, pz = g.oz + (z + 0.5F) * g.sz;
		if (!skeleton.holds(px, py, pz) || skeleton.patch(x, y, z)) return false;
		int[] texel = skeleton.texel(px, py, pz, d);
		if (!texture.solid(texel[0], texel[1])) return false;
		face(g, x, y, z, d, texture.uv(texel[0], texel[1]), matrix, p, n, bones, 1F);
		return true;
	}

	private static void face(Grid g, int x, int y, int z, int[] d, float[] uv, Matrix4f matrix, Vector3f p, Vector3f n, Sink sink, float shade) {
		float x0 = g.ox + x * g.sx, y0 = g.oy + y * g.sy, z0 = g.oz + z * g.sz;
		float x1 = x0 + g.sx, y1 = y0 + g.sy, z1 = z0 + g.sz;
		float[][] c;
		if (d[0] != 0) {
			float fx = d[0] > 0 ? x1 : x0;
			c = new float[][]{{fx, y0, z0}, {fx, y0, z1}, {fx, y1, z1}, {fx, y1, z0}};
		} else if (d[1] != 0) {
			float fy = d[1] > 0 ? y1 : y0;
			c = new float[][]{{x0, fy, z0}, {x1, fy, z0}, {x1, fy, z1}, {x0, fy, z1}};
		} else {
			float fz = d[2] > 0 ? z1 : z0;
			c = new float[][]{{x0, y0, fz}, {x1, y0, fz}, {x1, y1, fz}, {x0, y1, fz}};
		}
		float[][] t = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
		for (int k = 0; k < 4; k++) {
			matrix.transformPosition(c[k][0] / 16F, c[k][1] / 16F, c[k][2] / 16F, p);
			sink.vertex(p.x, p.y, p.z, t[k][0], t[k][1], n.x, n.y, n.z, shade);
		}
	}

	/**
	 * A square of meat four pixels wide at the plane of row {@code row}, facing +y ({@code down}) or -y, a little
	 * off the plane so it never fights with the skin that lies there: the neck of a head that came off.
	 */
	public static void neck(Grid g, int row, boolean down, PoseStack.Pose pose, Sink sink) {
		Matrix4f matrix = pose.pose();
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		float sign = down ? 1 : -1;
		float y = g.oy + g.sy * row + sign * 0.05F;
		float cx = g.ox + g.sx * g.gx / 2F, cz = g.oz + g.sz * g.gz / 2F;
		float half = 2F;
		pose.transformNormal(0, sign, 0, n);
		for (float x = cx - half; x < cx + half - 0.01F; x++) {
			for (float z = cz - half; z < cz + half - 0.01F; z++) {
				float[][] c = {{x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}, {x, y, z + 1}};
				float[][] t = {{MEAT_UV[0], MEAT_UV[1]}, {MEAT_UV[2], MEAT_UV[1]}, {MEAT_UV[2], MEAT_UV[3]}, {MEAT_UV[0], MEAT_UV[3]}};
				for (int k = 0; k < 4; k++) {
					matrix.transformPosition(c[k][0] / 16F, c[k][1] / 16F, c[k][2] / 16F, p);
					sink.vertex(p.x, p.y, p.z, t[k][0], t[k][1], n.x, n.y, n.z);
				}
			}
		}
	}

	/**
	 * A bone standing out of the cut at row {@code cut}, towards +y (out of a stump) or -y (out of a fallen piece).
	 */
	public static void bone(Grid g, int cut, float scale, boolean down, PoseStack.Pose pose, Sink sink) {
		List<float[]> quads = BoneMesh.quads();
		if (quads.isEmpty()) return;
		Matrix4f matrix = pose.pose();
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		float cx = g.ox + g.sx * g.gx / 2F, cz = g.oz + g.sz * g.gz / 2F;
		float cy = g.oy + g.sy * cut;
		float sign = down ? 1 : -1;
		// a pixel of the bone stays inside the flesh
		float base = cy - sign * Math.min(1F, g.sy);
		for (float[] q : quads) {
			for (int k = 0; k < 4; k++) {
				int o = k * 8;
				float x = cx + (q[o] - 8F) * scale, y = base + sign * q[o + 1] * scale, z = cz + (q[o + 2] - 8F) * scale;
				matrix.transformPosition(x / 16F, y / 16F, z / 16F, p);
				pose.transformNormal(q[o + 5], sign * q[o + 6], q[o + 7], n);
				sink.vertex(p.x, p.y, p.z, q[o + 3], q[o + 4], n.x, n.y, n.z);
			}
		}
	}
}
