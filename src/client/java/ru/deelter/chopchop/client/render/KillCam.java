package ru.deelter.chopchop.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ru.deelter.chopchop.client.config.ClientConfig;

/**
 * The kill cam of whoever took a head: for a couple of seconds the camera leaves the eyes, swings around the flying
 * head in a slow orbit with the body in the back of the picture, and comes back. Only the camera moves: the player
 * keeps walking and fighting, and the server knows nothing of it.
 * <p>
 * Smoothing as in VRCamera: the camera chases where the shot wants it with a spring that does not depend on the
 * frame rate, and it is kept out of walls by a ray from the head.
 */
public final class KillCam {
	private static final int EXPECT_TICKS = 20;
	/**
	 * the last ticks of the shot bring the camera back to the eyes
	 */
	private static final float BLEND_OUT_TICKS = 10;
	private static final double SPRING = 5.0;
	/**
	 * radians of orbit per tick
	 */
	private static final float ORBIT_SPEED = 0.03F;

	private static Debris.Chunk chunk;
	private static int victim = -1;
	private static int expecting;
	private static int ticks;
	private static float azimuth;
	private static Vec3 camera;
	private static long lastNanos;

	private KillCam() {
	}

	/**
	 * The server says this client just took the head of {@code entityId}: the head is taken as the piece it becomes.
	 */
	public static void expect(int entityId) {
		ClientConfig config = ClientConfig.get();
		if (!config.isKillCam() || !config.isDebris() || HeadCam.active() || HeadCam.expecting()) return;
		// with monsters about, the head just flies and the camera stays: unless the server freezes them for it
		if ("skip".equalsIgnoreCase(ru.deelter.chopchop.client.ChopChopClient.serverRules().getKillCamThreats()) && threatened(entityId)) return;
		victim = entityId;
		expecting = EXPECT_TICKS;
	}

	/**
	 * A live monster within 12 blocks, the victim aside: a dying one, or a body on the ground, is no threat.
	 */
	private static boolean threatened(int victimId) {
		var minecraft = net.minecraft.client.Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || minecraft.level == null) return false;
		return !minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(12),
				mob -> mob.getId() != victimId && mob.isAlive() && mob instanceof net.minecraft.world.entity.monster.Enemy).isEmpty();
	}

	static void offer(Debris.Chunk head, int entityId) {
		if (expecting <= 0 || entityId != victim) return;
		if (chunk != null) chunk.keep(false);
		chunk = head;
		chunk.keep(true);
		expecting = 0;
		ticks = 0;
		camera = null;
		Minecraft minecraft = Minecraft.getInstance();
		Vector3f at = head.position(0);
		// start from the side of the player, turn from there
		azimuth = minecraft.player == null ? 0
				: (float) Math.atan2(minecraft.player.getX() - at.x, minecraft.player.getZ() - at.z) + 0.6F;
	}

	public static boolean active() {
		return chunk != null;
	}

	public static void stop() {
		if (chunk != null) chunk.keep(false);
		chunk = null;
		expecting = 0;
		camera = null;
	}

	public static void tick(Minecraft minecraft) {
		if (expecting > 0) expecting--;
		if (chunk == null) return;
		// the player's own head came off meanwhile: that view wins
		if (++ticks > length() || minecraft.player == null || HeadCam.active()) stop();
	}

	private static float length() {
		return (float) ClientConfig.get().getKillCamSeconds() * 20;
	}

	/**
	 * @param eye   where the usual camera is this frame
	 * @param yaw   its yaw
	 * @param pitch its pitch
	 * @return the camera of the shot, or null when there is none
	 */
	public static HeadCam.View view(float partial, Vec3 eye, float yaw, float pitch) {
		Minecraft minecraft = Minecraft.getInstance();
		if (chunk == null || minecraft.level == null || minecraft.player == null) return null;
		float t = ticks + partial;
		Vector3f h = chunk.position(partial);
		Vec3 head = new Vec3(h.x, h.y, h.z);

		double distance = 2.4 + chunk.size() * 3;
		float around = azimuth + t * ORBIT_SPEED;
		Vec3 wanted = head.add(Math.sin(around) * distance, 0.9 + chunk.size(), Math.cos(around) * distance);
		// never through a wall: stop short of whatever is between the head and the spot
		BlockHitResult wall = minecraft.level.clip(new ClipContext(head, wanted, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, minecraft.player));
		if (wall.getType() != HitResult.Type.MISS) {
			wanted = wall.getLocation().add(head.subtract(wall.getLocation()).normalize().scale(0.3));
		}

		long now = System.nanoTime();
		double dt = camera == null ? 0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		camera = camera == null ? eye : camera.lerp(wanted, 1 - Math.exp(-SPRING * dt));

		// back into the eyes at the end, eased
		float out = Mth.clamp((length() - t) / BLEND_OUT_TICKS, 0, 1);
		float k = out * out * (3 - 2 * out);
		Vec3 position = eye.lerp(camera, k);
		Vec3 look = head.subtract(position);
		float lookYaw = (float) Math.atan2(-look.x, look.z) * Mth.RAD_TO_DEG;
		float lookPitch = (float) -Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)) * Mth.RAD_TO_DEG;
		return new HeadCam.View(position, Mth.rotLerp(k, yaw, lookYaw), Mth.lerp(k, pitch, lookPitch));
	}
}
