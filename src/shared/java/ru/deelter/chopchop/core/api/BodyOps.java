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
	 * The wounds of a body as plain Java values only (maps, strings, numbers, booleans), for a mod or a plugin that
	 * reads them through reflection without depending on ChopChop:
	 * <pre>
	 * profile    String   the body profile, "player"
	 * revision   Integer  counted up on every change
	 * headless   Boolean  the head came off
	 * legless    Boolean  every leg is cut off
	 * legsGone   Integer  legs cut off, at any height
	 * parts      Map&lt;String, Map&lt;String, Object&gt;&gt;  the damaged parts by name ("right_arm"), each:
	 *   carved     Integer  voxels carved out by wounds
	 *   voxels     Integer  voxels of the whole part
	 *   wounded    Double   carved / voxels, 0 to 1
	 *   severed    Boolean  cut off at some height
	 *   remaining  Double   share of the part still on, 0 (all gone) to 1 (whole length)
	 *   hand       String   "right" or "left" for an arm with a hand, absent otherwise
	 *   leg        Boolean  the body stands on it
	 * </pre>
	 * The maps are copies: changing them changes nothing.
	 */
	public static java.util.Map<String, Object> snapshot(WoundState state) {
		java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("profile", state.getProfile().getId());
		body.put("revision", state.getRevision());
		body.put("headless", state.headless());
		body.put("legless", state.legless());
		body.put("legsGone", state.legsGone());
		java.util.Map<String, Object> parts = new java.util.LinkedHashMap<>();
		for (int i = 0; i < state.partCount(); i++) {
			var part = state.part(i);
			if (part.pristine()) continue;
			BodyPart def = part.getPart();
			java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
			p.put("carved", part.carvedCount());
			p.put("voxels", def.volume());
			p.put("wounded", (double) part.carvedCount() / def.volume());
			p.put("severed", part.severed());
			p.put("remaining", part.severed() ? (double) part.getSeveredAt() / def.gy() : 1.0);
			if (def.getHand() != null) p.put("hand", def.getHand());
			p.put("leg", def.isLeg());
			parts.put(def.getName(), p);
		}
		body.put("parts", parts);
		return body;
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
	 * Cuts a body in two at the waist: the trunk at {@code along} (0 neck, 1 hips; 0.5 is the middle) and every leg at
	 * its root, so the lower half falls off. The upper half stays on the entity. Profiles with no part named "body"
	 * get nothing.
	 *
	 * @return the messages for the clients, empty if nothing more came off
	 */
	public static List<byte[]> halve(WoundBook book, UUID id, int entityId, WoundState state, double along, boolean fling, int attacker) {
		List<byte[]> messages = new ArrayList<>();
		BodyProfile profile = state.getProfile();
		BodyPart body = profile.part("body");
		if (body == null || body.gy() < 2) return messages;
		int row = Math.clamp((int) Math.round(Math.clamp(along, 0, 1) * body.gy()), 1, body.gy() - 1);
		messages.addAll(book.sever(id, entityId, state, body.getIndex(), row, null, fling, attacker));
		for (int i = 0; i < profile.getParts().size(); i++) {
			if (profile.part(i).isLeg()) messages.addAll(book.sever(id, entityId, state, i, 0, null, fling, attacker));
		}
		return messages;
	}

	/**
	 * Cuts a body in two from the top of the head down between the legs: the right halves of the head and the trunk
	 * and the whole right arm and leg ("right_*" parts) come off, the left side stays. The cut runs along the
	 * body's own left-right axis, whatever way the blow came.
	 *
	 * @return the messages for the clients, empty if nothing came off (no part named "body" or "head")
	 */
	public static List<byte[]> split(WoundBook book, UUID id, int entityId, WoundState state, boolean fling, int attacker) {
		List<byte[]> messages = new ArrayList<>();
		BodyProfile profile = state.getProfile();
		BodyPart body = profile.part("body"), head = profile.part("head");
		if (body == null || head == null) return messages;
		// the model's x runs from the right side of the body to the left: the low half is the right one
		for (BodyPart part : new BodyPart[]{head, body}) {
			messages.addAll(book.split(id, entityId, state, part.getIndex(), (part.gx() + 1) / 2, fling, attacker));
		}
		for (int i = 0; i < profile.getParts().size(); i++) {
			if (profile.part(i).getName().startsWith("right_")) messages.addAll(book.sever(id, entityId, state, i, 0, null, fling, attacker));
		}
		return messages;
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
