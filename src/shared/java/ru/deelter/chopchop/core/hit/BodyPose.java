package ru.deelter.chopchop.core.hit;

import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Where an entity stands and how its model is turned, as the server knows it. The server never sees the animated
 * pose a client draws, so hits are found on the rest pose of the profile, turned like the game turns the model.
 * <p>
 * After the yaw the model is moved by {@code lift} and {@code shift} and then pitched by {@code pitch}, in that order, at the
 * scale of the body. The client draws a crawling body with the same numbers (see {@link #crawling}).
 *
 * @param x       feet
 * @param bodyYaw body rotation in degrees, as {@code LivingEntity.yBodyRot}
 * @param scale   size of the model relative to the profile, 1 for an adult of normal size
 * @param pitch   extra rotation of the whole model about its x axis in degrees, for swimming, gliding and crawling;
 *                0 standing
 * @param lift    blocks the model is moved up before the pitch
 * @param shift   blocks the model is moved back (towards its own +z after the yaw) before the pitch
 */
public record BodyPose(double x, double y, double z, float bodyYaw, float scale, float pitch, float lift, float shift) {
	public BodyPose(double x, double y, double z, float bodyYaw, float scale, float pitch) {
		this(x, y, z, bodyYaw, scale, pitch, 0, 0);
	}

	/**
	 * A body with all its legs off lies as its profile says, when the rules let it crawl.
	 */
	public static boolean crawls(WoundState state, WoundRules rules) {
		return state != null && rules != null && rules.crawls() && state.getProfile().getCrawl() != null && state.legless();
	}

	/**
	 * This pose turned into the crawl of the profile; the pose itself if the body does not crawl.
	 */
	public BodyPose crawling(WoundState state, WoundRules rules) {
		if (!crawls(state, rules)) return this;
		BodyProfile.Crawl crawl = state.getProfile().getCrawl();
		return new BodyPose(x, y, z, bodyYaw, scale, crawl.pitch(), crawl.lift(), crawl.shift());
	}
}
