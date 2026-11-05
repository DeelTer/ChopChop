package ru.deelter.chopchop.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ru.deelter.chopchop.client.config.ClientConfig;

/**
 * The view of a player whose head came off: the camera rides in the head as it flies, tumbles and bounces, and
 * once it lies still the head turns to look at its own body falling over. The death screen waits a few seconds so
 * the show can be seen, then comes up over the same view; the camera stays in the head until respawn.
 * <p>
 * Taking the head needs the body drawn once from outside (the first person view does not draw it): for one frame
 * after the cut the camera is detached, the head is copied from that draw, and the camera moves into it.
 */
public final class HeadCam {
	/**
	 * how long the head is waited for after the cut, ticks
	 */
	private static final int EXPECT_TICKS = 10;
	// a headless body stands a while before it dies (headlessPlayerSeconds): the camera keeps to the head meanwhile
	private static final int MIN_TICKS_ALIVE = 120;

	private static Debris.Chunk chunk;
	private static int expecting;
	private static int ticks;
	private static Screen heldDeathScreen;
	private static boolean releasing;

	private HeadCam() {
	}

	/**
	 * The server said the local player's head came off: draw the body this frame to take the head from it.
	 */
	public static void expect() {
		if (!ClientConfig.get().isHeadCam() || !ClientConfig.get().isDebris()) return;
		expecting = EXPECT_TICKS;
		ticks = 0;
		// a blast kills inside the damage, before the wounds are worked out: the death screen may be up already
		if (heldDeathScreen == null && Minecraft.getInstance().gui instanceof Screens screens) heldDeathScreen = screens.chopchop$takeDeathScreen();
	}

	public static boolean expecting() {
		return expecting > 0;
	}

	static void follow(Debris.Chunk head) {
		if (!ClientConfig.get().isHeadCam()) return;
		if (chunk != null) chunk.keep(false);
		chunk = head;
		chunk.keep(true);
		expecting = 0;
		ticks = 0;
	}

	public static boolean active() {
		return chunk != null;
	}

	public static void stop() {
		if (chunk != null) chunk.keep(false);
		chunk = null;
		expecting = 0;
		release();
	}

	public static void tick(Minecraft minecraft) {
		if (expecting > 0 && --expecting == 0 && chunk == null) release();
		if (chunk == null) return;
		ticks++;
		// back alive: respawned, or a totem
		if (minecraft.player == null || ticks > MIN_TICKS_ALIVE && !minecraft.player.isDeadOrDying()) {
			stop();
			return;
		}
		// the player may cut it short: a click or a jump brings the death screen at once
		boolean skip = ticks > 10 && (minecraft.options.keyAttack.isDown() || minecraft.options.keyUse.isDown()
				|| minecraft.options.keyJump.isDown());
		if (heldDeathScreen != null && (skip || ticks >= ClientConfig.get().getHeadCamSeconds() * 20)) release();
	}

	/**
	 * @return true when the death screen is held back for now
	 */
	public static boolean holdDeathScreen(Screen screen) {
		// the death can arrive before the frame that takes the head: hold it while the head is awaited too
		if (releasing || chunk == null && expecting == 0 || ticks >= ClientConfig.get().getHeadCamSeconds() * 20) return false;
		heldDeathScreen = screen;
		return true;
	}

	public static boolean holdingDeathScreen() {
		return heldDeathScreen != null && !releasing;
	}

	private static void release() {
		Screen screen = heldDeathScreen;
		heldDeathScreen = null;
		Minecraft minecraft = Minecraft.getInstance();
		if (screen == null || minecraft.player == null || !minecraft.player.isDeadOrDying()) return;
		releasing = true;
		try {
			minecraft.gui.setScreen(screen);
			// the head cam was the wait: the buttons work at once
			if (screen instanceof ru.deelter.chopchop.mixin.client.DeathScreenAccessor death) {
				death.chopchop$setDelayTicker(20);
				death.chopchop$setButtonsActive(true);
			}
		} finally {
			releasing = false;
		}
	}

	/**
	 * Where the camera is: just in front of the face, looking out of the eyes.
	 *
	 * @return position, yaw and pitch, or null when the camera is the usual one
	 */
	public static View view(float partial) {
		if (chunk == null) return null;
		Vector3f center = chunk.position(partial);
		// the face of a model looks along its -z
		Vector3f forward = chunk.orientation(partial).transform(new Vector3f(0, 0, -1));
		Vec3 eye = new Vec3(center.x + forward.x * chunk.size() * 0.6, center.y + forward.y * chunk.size() * 0.6,
				center.z + forward.z * chunk.size() * 0.6);
		float yaw = (float) Math.atan2(-forward.x, forward.z) * Mth.RAD_TO_DEG;
		float pitch = (float) -Math.asin(Mth.clamp(forward.y, -1.0F, 1.0F)) * Mth.RAD_TO_DEG;
		return new View(eye, yaw, pitch);
	}

	public record View(Vec3 position, float yaw, float pitch) {
	}

	/**
	 * What the mixin on the HUD lets this class do.
	 */
	public interface Screens {
		Screen chopchop$takeDeathScreen();
	}
}
