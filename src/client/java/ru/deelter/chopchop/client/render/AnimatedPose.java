package ru.deelter.chopchop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.core.profile.BodyProfile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pose each body near the camera was last drawn in: for every part of its profile, the matrix from the part to
 * the world (relative to the camera of that frame). The crosshair casts against it, so a swinging arm is hit where
 * it is drawn, not where it stands at rest. Taken only for bodies within reach of an attack, a few matrices each.
 * Render thread only.
 */
public final class AnimatedPose {
	/**
	 * bodies further than this from the camera are not taken, blocks
	 */
	private static final double RANGE = 8;
	/**
	 * older than this a pose is not used, the body moved on
	 */
	private static final long FRESH_NANOS = 150_000_000L;
	private static final Map<Integer, Snapshot> POSES = new HashMap<>();

	/**
	 * @param parts  part to camera relative world, per part of the profile; null where the part is not drawn
	 * @param camera where the camera was
	 */
	public record Snapshot(BodyProfile profile, Matrix4f[] parts, double[] camera, long nanos) {
		public boolean fresh() {
			return System.nanoTime() - nanos < FRESH_NANOS;
		}
	}

	private AnimatedPose() {
	}

	public static void capture(Model<?> model, int entityId, double distanceSq, PoseStack.Pose pose, double[] camera) {
		if (distanceSq > RANGE * RANGE) return;
		// a dead player's body too: it is in no world, but it can be cut
		if (!(ru.deelter.chopchop.client.ClientEntities.find(entityId) instanceof LivingEntity entity)) return;
		BodyProfile profile = ChopChop.getProfiles().forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
		if (profile == null) return;
		Bindings.ensure(model);
		int count = profile.getParts().size();
		Matrix4f[] parts = new Matrix4f[count];
		boolean any = false;
		for (int i = 0; i < count; i++) {
			List<ModelPart> path = Bindings.path(model, profile.part(i).getName());
			if (path == null || path.isEmpty() || !path.getLast().visible) continue;
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			for (ModelPart p : path) p.translateAndRotate(stack);
			parts[i] = new Matrix4f(stack.last().pose());
			any = true;
		}
		if (any) POSES.put(entityId, new Snapshot(profile, parts, camera.clone(), System.nanoTime()));
	}

	public static Snapshot get(int entityId) {
		Snapshot snapshot = POSES.get(entityId);
		return snapshot != null && snapshot.fresh() ? snapshot : null;
	}

	/**
	 * Drops what is old, once in a while.
	 */
	public static void tick() {
		if (POSES.isEmpty()) return;
		POSES.values().removeIf(s -> System.nanoTime() - s.nanos() > FRESH_NANOS * 10);
	}

	public static void clear() {
		POSES.clear();
	}
}
