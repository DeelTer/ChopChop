package ru.deelter.chopchop.core.hit;

import org.joml.Vector3d;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Whether the server takes a client's word for where a blow landed ({@link Protocol#C_AIM}). The client saw the
 * animated pose, the server only knows the rest pose, so the voxel the client names is checked against the rest
 * pose with room for the animation: an arm swings, it does not leave the body.
 * <ul>
 *     <li>the part and voxel exist and the voxel is still there (not carved, not cut off);</li>
 *     <li>the voxel, where it is at rest, is within reach of the attacker's eyes, with room for a swing;</li>
 *     <li>it lies near the attacker's look: within {@link #MAX_OFF_LOOK} blocks of the look ray, which is about as
 *     far as a limb moves in any animation.</li>
 * </ul>
 * A cheating client can at most choose another spot of the body it is really hitting, close to where it looks: no
 * more damage, no reach, no other target. The hit itself is still the vanilla one, checked by the game.
 */
public final class AimCheck {
	/**
	 * blocks a part of a body may be off its rest pose in an animation, plus a margin
	 */
	public static final double MAX_OFF_LOOK = 0.9;
	/**
	 * blocks of reach added for the animation
	 */
	public static final double REACH_MARGIN = 1.0;

	private AimCheck() {
	}

	/**
	 * @param look unit length
	 * @return the hit to use, or null to find it the usual way
	 */
	public static Hit accept(WoundState state, BodyPose pose, Protocol.Aim aim, double ex, double ey, double ez,
							 double lx, double ly, double lz, double reach) {
		if (aim == null || aim.part() < 0 || aim.part() >= state.partCount()) return null;
		BodyPart part = state.getProfile().part(aim.part());
		if (!part.isWoundable() && !part.severable()) return null;
		PartState partState = state.part(aim.part());
		if (!partState.inside(aim.x(), aim.y(), aim.z()) || partState.gone(aim.x(), aim.y(), aim.z())) return null;
		Vector3d model = HitLocator.voxelCenter(state.getProfile(), part, aim.x(), aim.y(), aim.z());
		Vector3d world = HitLocator.toWorld(pose, model.x, model.y, model.z);
		double dx = world.x - ex, dy = world.y - ey, dz = world.z - ez;
		double along = dx * lx + dy * ly + dz * lz;
		if (along < -0.5 || along > reach + REACH_MARGIN) return null;
		double ox = dx - lx * along, oy = dy - ly * along, oz = dz - lz * along;
		if (Math.sqrt(ox * ox + oy * oy + oz * oz) > MAX_OFF_LOOK * Math.max(0.5, pose.scale())) return null;
		return new Hit(aim.part(), aim.x(), aim.y(), aim.z(), new float[]{(float) model.x, (float) model.y, (float) model.z},
				Math.max(0, along), aim.slash(), aim.inward());
	}
}
