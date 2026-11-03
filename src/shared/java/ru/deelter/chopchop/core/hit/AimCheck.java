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
	 * A blow at a dead body: the server no longer has the body's entity nor knows how it lies (each client lets it
	 * fall), so only the part and voxel are checked. Cutting a body is only for the eye, nothing in the game hangs
	 * on it; how near the player is, the server checks itself.
	 *
	 * @return the hit, or null if the spot is not on the body any more
	 */
	public static Hit corpse(WoundState state, Protocol.Aim cut) {
		if (cut == null || cut.part() < 0 || cut.part() >= state.partCount()) return null;
		BodyPart part = state.getProfile().part(cut.part());
		if (!part.isWoundable() && !part.severable()) return null;
		PartState partState = state.part(cut.part());
		if (!partState.inside(cut.x(), cut.y(), cut.z()) || partState.gone(cut.x(), cut.y(), cut.z())) return null;
		Vector3d model = HitLocator.voxelCenter(state.getProfile(), part, cut.x(), cut.y(), cut.z());
		return new Hit(cut.part(), cut.x(), cut.y(), cut.z(), new float[]{(float) model.x, (float) model.y, (float) model.z}, 0,
				cut.slash(), cut.inward());
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
		// the way in decides which side a wound may dig from: it has to agree with the look, or a client could open
		// the far side of a part
		float[] inward = ru.deelter.chopchop.core.api.BodyOps.gridDirection(state, pose, aim.part(), lx, ly, lz);
		float[] claimed = aim.inward();
		if (claimed != null && inward != null && claimed[0] * inward[0] + claimed[1] * inward[1] + claimed[2] * inward[2] > 0.3) inward = claimed;
		return new Hit(aim.part(), aim.x(), aim.y(), aim.z(), new float[]{(float) model.x, (float) model.y, (float) model.z},
				Math.max(0, along), aim.slash(), inward);
	}
}
