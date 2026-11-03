package ru.deelter.chopchop.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

/**
 * Whether something can go without the player seeing it go: out of the view, far away, or in a chunk that is not
 * loaded any more.
 */
public final class Unseen {
	/**
	 * further than this nobody notices a piece go
	 */
	private static final double FAR = 48;
	/**
	 * cosine of the angle from the look beyond which a point is out of the view, with a margin for wide fields of view
	 */
	private static final double VIEW_COS = 0.3;

	private Unseen() {
	}

	public static boolean unseen(Minecraft minecraft, Vec3 at) {
		var camera = minecraft.getCameraEntity();
		if (camera == null) return true;
		Vec3 eye = camera.getEyePosition(1F);
		Vec3 to = at.subtract(eye);
		double distance = to.length();
		if (distance > FAR) return true;
		if (distance < 1.5) return false;
		Vec3 look = camera.getViewVector(1F);
		// in the third person the camera looks from behind or in front: either way a piece near the player is in view
		if (!minecraft.options.getCameraType().isFirstPerson()) return distance > 6 && to.dot(look) / distance < VIEW_COS;
		return to.dot(look) / distance < VIEW_COS;
	}

	public static boolean unloaded(ClientLevel level, Vec3 at) {
		return !level.hasChunk(SectionPos.blockToSectionCoord(at.x), SectionPos.blockToSectionCoord(at.z));
	}
}
