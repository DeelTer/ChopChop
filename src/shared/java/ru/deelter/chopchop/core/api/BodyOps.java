package ru.deelter.chopchop.core.api;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.server.WoundBook;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the public API of both backends does, without game classes: the Fabric and the Paper API only turn their
 * entities and vectors into these calls, so a combat mod gets the same result on either server.
 */
public final class BodyOps {
	private BodyOps() {
	}

	/**
	 * A spot on a body found by a ray.
	 *
	 * @param part     name of the part, "right_arm"
	 * @param along    where along the part, 0 at its root (shoulder, hip, neck) to 1 at its end
	 * @param world    the point in the world
	 * @param internal the hit as the core knows it
	 */
	public record Spot(String part, double along, double[] world, Hit internal) {
	}

	public static Spot locate(WoundState state, BodyPose pose, double ox, double oy, double oz, double dx, double dy, double dz,
							  double reach, boolean nearest) {
		Hit hit = nearest ? HitLocator.castOrNearest(state, pose, ox, oy, oz, dx, dy, dz, reach)
				: HitLocator.cast(state, pose, ox, oy, oz, dx, dy, dz, reach);
		if (hit == null) return null;
		BodyPart part = state.getProfile().part(hit.part());
		Vector3d world = HitLocator.toWorld(pose, hit.model()[0], hit.model()[1], hit.model()[2]);
		return new Spot(part.getName(), (hit.y() + 0.5) / part.gy(), new double[]{world.x, world.y, world.z}, hit);
	}

	/**
	 * The row of voxels a cut at {@code along} (0 root, 1 end) goes through. A head comes off whole at any value.
	 */
	public static int rowAt(BodyPart part, double along) {
		if (part.isHead()) return 0;
		int row = (int) Math.round(Math.clamp(along, 0, 1) * part.gy());
		return Math.clamp(row, 0, part.gy() - 1);
	}

	/**
	 * A direction in the world as a direction in the voxel grid of a part, unit length; null if it has none there.
	 */
	public static float[] gridDirection(WoundState state, BodyPose pose, int partIndex, double wx, double wy, double wz) {
		BodyProfile profile = state.getProfile();
		BodyPart part = profile.part(partIndex);
		Vector3d model = HitLocator.toModel(pose, wx, wy, wz, false);
		Matrix4d toLocal = HitLocator.partToModel(profile, part).invert();
		Vector3d local = toLocal.transformDirection(model);
		float[] b = part.getBox();
		double[] g = {local.x * part.gx() / Math.max(1e-6, b[3] - b[0]), local.y * part.gy() / Math.max(1e-6, b[4] - b[1]),
				local.z * part.gz() / Math.max(1e-6, b[5] - b[2])};
		double length = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
		if (length < 1e-9) return null;
		return new float[]{(float) (g[0] / length), (float) (g[1] / length), (float) (g[2] / length)};
	}

	/**
	 * Severs a named part at {@code along}.
	 *
	 * @return the messages for the clients, empty if nothing more came off or there is no such part
	 */
	public static List<byte[]> sever(WoundBook book, UUID id, int entityId, WoundState state, String partName, double along,
									 boolean fling, int attacker) {
		BodyPart part = state.getProfile().part(partName);
		if (part == null) return new ArrayList<>();
		return book.sever(id, entityId, state, part.getIndex(), rowAt(part, along), null, fling, attacker);
	}

	/**
	 * A wound at a spot: a round hole, or with a swing direction a cut along it.
	 *
	 * @param swing  the way the blade moved, in the world; null for a hole
	 * @param length length of the cut in voxels (about pixels of the model)
	 * @param radius radius of the hole or half the width of the cut, voxels
	 * @param depth  voxels under the surface
	 */
	public static List<byte[]> wound(WoundBook book, UUID id, int entityId, WoundState state, BodyPose pose, Spot spot, double[] swing,
									 double length, double radius, int depth, int attacker) {
		float[] axis = swing == null ? null : gridDirection(state, pose, spot.internal().part(), swing[0], swing[1], swing[2]);
		return book.wound(id, entityId, state, spot.internal(), axis, axis == null ? 0 : length / 2, radius, depth, attacker);
	}
}
