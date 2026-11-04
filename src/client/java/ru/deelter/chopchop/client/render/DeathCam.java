package ru.deelter.chopchop.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.client.Corpses;
import ru.deelter.chopchop.client.config.ClientConfig;

/**
 * A player who dies sees the own body: the camera leaves the eyes (the first person view lies inside the body and
 * shows nothing of it), rises to the side the body falls across and turns slowly around it until respawn. The
 * death screen comes as usual, over this view. A head that came off has its own view ({@link HeadCam}), which wins.
 * <p>
 * Smoothing as in VRCamera: the camera chases the spot of the shot with a spring that does not depend on the frame
 * rate, and a ray from the body keeps it out of walls.
 */
public final class DeathCam {
	private static final double SPRING = 3.0;
	/**
	 * radians of orbit per tick
	 */
	private static final float ORBIT_SPEED = 0.006F;
	private static final double DISTANCE = 3.2, HEIGHT = 1.8;
	/**
	 * ticks of moving from the eyes into the shot
	 */
	private static final float BLEND_IN_TICKS = 20;

	// ids of bodies of players are negative: no id is free to mean "none"
	private static boolean following;
	private static int corpse;
	private static int ticks;
	private static float azimuth;
	private static Vec3 camera;
	private static long lastNanos;

	private DeathCam() {
	}

	/**
	 * The local player's own body lies there now, the copy that is drawn for it.
	 *
	 * @param dx the way the last blow pushed it
	 */
	public static void follow(int corpseId, float dx, float dz) {
		if (!ClientConfig.get().isOwnCorpse()) return;
		following = true;
		corpse = corpseId;
		ticks = 0;
		camera = null;
		// across the way it falls, so the fall is seen from the side
		azimuth = (float) Math.atan2(dx, dz) + (float) (Math.PI / 2);
	}

	public static boolean active() {
		return following;
	}

	public static void stop() {
		following = false;
		camera = null;
	}

	public static void tick(Minecraft minecraft) {
		if (!active()) return;
		ticks++;
		// back alive (respawn, a totem), or the body is gone
		if (minecraft.player == null || ticks > 10 && !minecraft.player.isDeadOrDying() || Corpses.get(corpse) == null) stop();
	}

	/**
	 * @return the camera of the shot, or null when there is none
	 */
	public static HeadCam.View view(float partial, Vec3 eye, float yaw, float pitch) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!active() || HeadCam.active() || HeadCam.expecting() || minecraft.level == null || minecraft.player == null) return null;
		Vec3 body = Corpses.middle(corpse);
		if (body == null) return null;
		float t = ticks + partial;
		float around = azimuth + t * ORBIT_SPEED;
		Vec3 wanted = body.add(Math.sin(around) * DISTANCE, HEIGHT, Math.cos(around) * DISTANCE);
		BlockHitResult wall = minecraft.level.clip(new ClipContext(body, wanted, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
		if (wall.getType() != HitResult.Type.MISS) {
			wanted = wall.getLocation().add(body.subtract(wall.getLocation()).normalize().scale(0.3));
		}
		long now = System.nanoTime();
		double dt = camera == null ? 0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		camera = camera == null ? eye : camera.lerp(wanted, 1 - Math.exp(-SPRING * dt));

		// out of the eyes, eased
		float in = Mth.clamp(t / BLEND_IN_TICKS, 0, 1);
		float k = in * in * (3 - 2 * in);
		Vec3 position = eye.lerp(camera, k);
		Vec3 look = body.subtract(position);
		float lookYaw = (float) Math.atan2(-look.x, look.z) * Mth.RAD_TO_DEG;
		float lookPitch = (float) -Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * Mth.RAD_TO_DEG;
		return new HeadCam.View(position, Mth.rotLerp(k, yaw, lookYaw), Mth.lerp(k, pitch, lookPitch));
	}
}
