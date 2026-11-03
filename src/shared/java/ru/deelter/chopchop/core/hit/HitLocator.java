package ru.deelter.chopchop.core.hit;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Finds the voxel a ray hits on the rest pose of a body.
 * <p>
 * World to model space is the inverse of what {@code LivingEntityRenderer.submit} does: scale, turn by
 * {@code 180 - bodyYaw} about y, flip x and y, move down by 1.501 blocks, and pixels are 1/16 of a block. Inside a
 * part the ray walks the voxel grid and stops at the first voxel that is still there, so a second hit at the same
 * spot goes deeper into the wound instead of hitting air.
 */
public final class HitLocator {
	private static final double MODEL_OFFSET = 1.501;
	private static final int MAX_STEPS = 64;

	private HitLocator() {
	}

	/**
	 * Transforms a world point (or direction, with {@code point} false) into model space.
	 */
	public static Vector3d toModel(BodyPose pose, double wx, double wy, double wz, boolean point) {
		Vector3d v = point ? new Vector3d(wx - pose.x(), wy - pose.y(), wz - pose.z()) : new Vector3d(wx, wy, wz);
		v.rotateY(-Math.toRadians(180.0 - pose.bodyYaw()));
		v.div(Math.max(0.05, pose.scale()));
		if (point) {
			v.y -= pose.lift();
			v.z -= pose.shift();
		}
		if (pose.pitch() != 0) v.rotateX(-Math.toRadians(pose.pitch()));
		if (point) {
			return new Vector3d(-v.x * 16, (MODEL_OFFSET - v.y) * 16, v.z * 16);
		}
		return new Vector3d(-v.x * 16, -v.y * 16, v.z * 16);
	}

	/**
	 * Back from model space to the world, for effects.
	 */
	public static Vector3d toWorld(BodyPose pose, double mx, double my, double mz) {
		Vector3d v = new Vector3d(-mx / 16, MODEL_OFFSET - my / 16, mz / 16);
		if (pose.pitch() != 0) v.rotateX(Math.toRadians(pose.pitch()));
		v.y += pose.lift();
		v.z += pose.shift();
		v.mul(Math.max(0.05, pose.scale()));
		v.rotateY(Math.toRadians(180.0 - pose.bodyYaw()));
		return v.add(pose.x(), pose.y(), pose.z());
	}

	/**
	 * Model space from part space, at rest.
	 */
	public static Matrix4d partToModel(BodyProfile profile, BodyPart part) {
		Matrix4d m = part.getParent() == null ? new Matrix4d() : partToModel(profile, profile.part(part.getParent()));
		float[] p = part.getPivot();
		float[] r = part.getRot();
		return m.translate(p[0], p[1], p[2]).rotateZYX(r[2], r[1], r[0]);
	}

	/**
	 * Center of a voxel in model space.
	 */
	public static Vector3d voxelCenter(BodyProfile profile, BodyPart part, int x, int y, int z) {
		float[] b = part.getBox();
		Vector3d local = new Vector3d(
				b[0] + (x + 0.5) * (b[3] - b[0]) / part.gx(),
				b[1] + (y + 0.5) * (b[4] - b[1]) / part.gy(),
				b[2] + (z + 0.5) * (b[5] - b[2]) / part.gz());
		return partToModel(profile, part).transformPosition(local);
	}

	/**
	 * The nearest voxel still there along a ray, or null.
	 *
	 * @param length how far the ray reaches, in blocks
	 */
	public static Hit cast(WoundState state, BodyPose pose, double ox, double oy, double oz,
						   double dx, double dy, double dz, double length) {
		Vector3d origin = toModel(pose, ox, oy, oz, true);
		Vector3d dir = toModel(pose, dx, dy, dz, false);
		Vector3d up = toModel(pose, 0, 1, 0, false);
		double worldLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (worldLength < 1e-9) return null;
		// the transform is affine: the ray parameter is the same in the world and in model space
		double maxT = length / worldLength;
		BodyProfile profile = state.getProfile();
		Hit best = null;
		for (int i = 0; i < state.partCount(); i++) {
			BodyPart part = profile.part(i);
			if (!part.isWoundable() && !part.severable()) continue;
			Hit hit = castPart(profile, part, state.part(i), origin, dir, up, maxT);
			if (hit != null && (best == null || hit.depth() < best.depth())) best = hit;
		}
		return best;
	}

	/**
	 * Rays from a blast to random spots of the body's box: the spots it tears at, all on the side facing the blast.
	 *
	 * @param width  of the entity's box, blocks
	 * @param height of the entity's box, blocks
	 */
	public static java.util.List<Hit> blast(WoundState state, BodyPose pose, double sx, double sy, double sz, double width, double height,
											int rays, java.util.random.RandomGenerator random) {
		java.util.List<Hit> hits = new java.util.ArrayList<>();
		for (int i = 0; i < rays * 2 && hits.size() < rays; i++) {
			double tx = pose.x() + (random.nextDouble() - 0.5) * width * 0.9;
			double ty = pose.y() + 0.1 + random.nextDouble() * (height - 0.15);
			double tz = pose.z() + (random.nextDouble() - 0.5) * width * 0.9;
			double dx = tx - sx, dy = ty - sy, dz = tz - sz;
			double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (length < 1e-4) {
				// right in the middle of it: from below
				dx = 0;
				dy = 1;
				dz = 0;
				sy = pose.y() - 0.5;
				length = 1;
			}
			Hit hit = cast(state, pose, sx, sy, sz, dx / length, dy / length, dz / length, length + 2);
			if (hit != null) hits.add(hit);
		}
		return hits;
	}

	/**
	 * A point on a part on its side facing a world point, in model space: where a blast meets a head.
	 */
	public static float[] facing(BodyProfile profile, BodyPart part, BodyPose pose, double sx, double sy, double sz) {
		float[] b = part.getBox();
		Vector3d center = partToModel(profile, part).transformPosition(new Vector3d((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2));
		Vector3d toward = toModel(pose, sx, sy, sz, true).sub(center);
		if (toward.lengthSquared() > 1e-6) center.add(toward.normalize(3));
		return new float[]{(float) center.x, (float) center.y, (float) center.z};
	}

	/**
	 * For melee: the server knows where the attacker looked, not what the client drew. When the look ray misses
	 * every part (the swing hit the collision box, not the model), aim again at the part nearest to that ray.
	 */
	public static Hit castOrNearest(WoundState state, BodyPose pose, double ox, double oy, double oz,
									double dx, double dy, double dz, double length) {
		Hit hit = cast(state, pose, ox, oy, oz, dx, dy, dz, length);
		if (hit != null) return hit;
		Vector3d origin = toModel(pose, ox, oy, oz, true);
		Vector3d dir = toModel(pose, dx, dy, dz, false).normalize();
		BodyProfile profile = state.getProfile();
		int bestPart = -1;
		double bestDistance = Double.MAX_VALUE;
		Vector3d bestCenter = null;
		for (int i = 0; i < state.partCount(); i++) {
			BodyPart part = profile.part(i);
			if (!part.isWoundable() && !part.severable()) continue;
			Vector3d center = voxelCenter(profile, part, part.gx() / 2, Math.min(part.gy() - 1, part.gy() / 2), part.gz() / 2);
			Vector3d to = new Vector3d(center).sub(origin);
			double along = Math.max(0, to.dot(dir));
			double distance = new Vector3d(dir).mul(along).add(origin).distance(center);
			if (distance < bestDistance) {
				bestDistance = distance;
				bestPart = i;
				bestCenter = center;
			}
		}
		if (bestPart < 0) return null;
		Vector3d aim = new Vector3d(bestCenter).sub(origin);
		return castPart(profile, profile.part(bestPart), state.part(bestPart), origin, aim, toModel(pose, 0, 1, 0, false), Double.MAX_VALUE / 4);
	}

	/**
	 * The way a swing cuts: across the blow and level in the world, as a sword or an axe sweeps. In voxels of the
	 * grid. Null when the blow comes straight down or up and has no sideways.
	 */
	private static float[] slash(Matrix4d toLocal, Vector3d dir, Vector3d modelUp, int[] grid, double[] size) {
		Vector3d up = toLocal.transformDirection(new Vector3d(modelUp));
		Vector3d across = new Vector3d(dir).cross(up);
		if (across.lengthSquared() < 1e-9) return null;
		double[] a = {across.x, across.y, across.z};
		double length = 0;
		for (int i = 0; i < 3; i++) {
			a[i] *= grid[i] / Math.max(1e-6, size[i]);
			length += a[i] * a[i];
		}
		length = Math.sqrt(length);
		if (length < 1e-9) return null;
		return new float[]{(float) (a[0] / length), (float) (a[1] / length), (float) (a[2] / length)};
	}

	private static Hit castPart(BodyProfile profile, BodyPart part, PartState partState, Vector3d origin, Vector3d dir, Vector3d up, double maxT) {
		Matrix4d toLocal = partToModel(profile, part).invert();
		Vector3d o = toLocal.transformPosition(new Vector3d(origin));
		Vector3d d = toLocal.transformDirection(new Vector3d(dir));
		float[] b = part.getBox();
		int[] grid = {part.gx(), part.gy(), part.gz()};
		double[] min = {b[0], b[1], b[2]};
		double[] size = {b[3] - b[0], b[4] - b[1], b[5] - b[2]};
		// into grid coordinates: one unit per voxel
		double[] go = new double[3];
		double[] gd = new double[3];
		double[] oa = {o.x, o.y, o.z};
		double[] da = {d.x, d.y, d.z};
		for (int a = 0; a < 3; a++) {
			double scale = grid[a] / Math.max(1e-6, size[a]);
			go[a] = (oa[a] - min[a]) * scale;
			gd[a] = da[a] * scale;
		}
		// slab test against the whole grid
		double tEnter = 0, tExit = maxT;
		for (int a = 0; a < 3; a++) {
			if (Math.abs(gd[a]) < 1e-12) {
				if (go[a] < 0 || go[a] > grid[a]) return null;
				continue;
			}
			double t1 = (0 - go[a]) / gd[a];
			double t2 = (grid[a] - go[a]) / gd[a];
			tEnter = Math.max(tEnter, Math.min(t1, t2));
			tExit = Math.min(tExit, Math.max(t1, t2));
		}
		if (tEnter > tExit) return null;
		// walk the voxels from the entry point
		double t = tEnter;
		int[] cell = new int[3];
		int[] step = new int[3];
		double[] tMax = new double[3];
		double[] tDelta = new double[3];
		for (int a = 0; a < 3; a++) {
			double p = go[a] + gd[a] * (t + 1e-7);
			cell[a] = Math.min(grid[a] - 1, Math.max(0, (int) Math.floor(p)));
			step[a] = gd[a] > 0 ? 1 : -1;
			if (Math.abs(gd[a]) < 1e-12) {
				tMax[a] = Double.MAX_VALUE;
				tDelta[a] = Double.MAX_VALUE;
			} else {
				double boundary = cell[a] + (gd[a] > 0 ? 1 : 0);
				tMax[a] = (boundary - go[a]) / gd[a];
				tDelta[a] = Math.abs(1 / gd[a]);
			}
		}
		for (int i = 0; i < MAX_STEPS; i++) {
			if (!partState.inside(cell[0], cell[1], cell[2]) || t > tExit) return null;
			if (!partState.gone(cell[0], cell[1], cell[2])) {
				Vector3d local = new Vector3d(
						min[0] + (go[0] + gd[0] * t) * size[0] / grid[0],
						min[1] + (go[1] + gd[1] * t) * size[1] / grid[1],
						min[2] + (go[2] + gd[2] * t) * size[2] / grid[2]);
				Vector3d model = partToModel(profile, part).transformPosition(local);
				double inLength = Math.sqrt(gd[0] * gd[0] + gd[1] * gd[1] + gd[2] * gd[2]);
				float[] inward = inLength < 1e-12 ? null
						: new float[]{(float) (gd[0] / inLength), (float) (gd[1] / inLength), (float) (gd[2] / inLength)};
				return new Hit(part.getIndex(), cell[0], cell[1], cell[2], new float[]{(float) model.x, (float) model.y, (float) model.z}, t,
						slash(toLocal, d, up, grid, size), inward);
			}
			int a = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
			t = tMax[a];
			tMax[a] += tDelta[a];
			cell[a] += step[a];
		}
		return null;
	}
}
