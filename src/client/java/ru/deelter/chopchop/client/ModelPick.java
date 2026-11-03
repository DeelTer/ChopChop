package ru.deelter.chopchop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The crosshair finds bodies by their model too, not only by their collision box: a zombie's arms reach out of its
 * box, and now they can be hit. Runs right after the vanilla pick, only when it found no entity, only on a server
 * with ChopChop, and only against the few bodies within reach.
 * <p>
 * The attack still goes through vanilla: the server accepts a hit on an entity whose box is within the reach plus a
 * margin, which an arm in front of the box always is. The server then finds the arm with the same rest pose ray.
 * The model used is the rest pose of the profile, as on the server, so what the client picks is what the server hits.
 */
public final class ModelPick {
	/**
	 * a whole body of each profile, to cast against entities without wounds; never changed
	 */
	private static final Map<BodyProfile, WoundState> WHOLE = new IdentityHashMap<>();

	private ModelPick() {
	}

	public static void afterPick(Minecraft minecraft, float partial) {
		if (!ChopChopClient.serverSpeaks() || minecraft.player == null || minecraft.level == null) return;
		HitResult current = minecraft.hitResult;
		if (current instanceof EntityHitResult) return;
		Entity viewer = minecraft.getCameraEntity();
		if (viewer == null || viewer != minecraft.player || minecraft.player.isSpectator()) return;
		double reach = minecraft.player.entityInteractionRange();
		Vec3 eye = viewer.getEyePosition(partial);
		Vec3 look = viewer.getViewVector(partial);
		// a block in the way stops the ray where vanilla stopped
		double limit = current != null && current.getType() != HitResult.Type.MISS ? Math.min(reach, current.getLocation().distanceTo(eye)) : reach;
		AABB around = viewer.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.5);
		LivingEntity best = null;
		double bestDistance = limit;
		Vec3 bestPoint = null;
		for (LivingEntity entity : minecraft.level.getEntitiesOfClass(LivingEntity.class, around,
				e -> e != viewer && e.isPickable() && !e.isSpectator() && !e.isInvisible())) {
			BodyProfile profile = ChopChop.getProfiles().forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			if (profile == null) continue;
			WoundState wounds = ClientWounds.get(entity.getId());
			WoundState state = wounds != null && wounds.getProfile() == profile ? wounds : WHOLE.computeIfAbsent(profile, WoundState::new);
			Vec3 at = entity.getPosition(partial);
			float yaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
			BodyPose pose = new BodyPose(at.x, at.y, at.z, yaw, entity.getScale(), 0).crawling(state, ChopChopClient.serverRules());
			Hit hit = HitLocator.cast(state, pose, eye.x, eye.y, eye.z, look.x, look.y, look.z, bestDistance);
			if (hit == null || hit.depth() >= bestDistance) continue;
			best = entity;
			bestDistance = hit.depth();
			var world = HitLocator.toWorld(pose, hit.model()[0], hit.model()[1], hit.model()[2]);
			bestPoint = new Vec3(world.x, world.y, world.z);
		}
		if (best == null) return;
		minecraft.hitResult = new EntityHitResult(best, bestPoint);
		minecraft.crosshairPickEntity = best;
	}
}
