package ru.deelter.chopchop.server;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.rules.WeaponKind;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Where on the body a damage source hit, from what the server knows.
 * <ul>
 *     <li>Arrows and tridents: their path over the last tick, swept through the body.</li>
 *     <li>Melee: the attacker's look (a player) or the line from its eyes to the target (a mob), then the part
 *     nearest to it if it misses the model. The client draws an animation the server never sees, so this is an
 *     approximation of the rest pose, not of the exact swing.</li>
 *     <li>Anything else: nothing, or with {@code otherSources: coarse} a ray from the source, or up the legs for
 *     falls.</li>
 * </ul>
 */
final class FabricHits {
	private FabricHits() {
	}

	/**
	 * @param sharpness level of Sharpness on the weapon, 0 for anything but a blade swung
	 */
	record Resolved(Hit hit, WeaponKind weapon, int sharpness) {
		Resolved(Hit hit, WeaponKind weapon) {
			this(hit, weapon, 0);
		}

	}

	static BodyPose pose(LivingEntity entity) {
		float pitch = 0;
		// as AvatarRenderer turns a swimming body: with the look in water, flat when crawling on land
		if (entity.isVisuallySwimming() || entity.isFallFlying()) pitch = entity.isInWater() || entity.isFallFlying() ? -90 - entity.getXRot() : -90;
		return new BodyPose(entity.getX(), entity.getY(), entity.getZ(), entity.yBodyRot, entity.getScale(), pitch);
	}

	static Resolved resolve(LivingEntity target, DamageSource source, WoundState state, WoundRules rules) {
		// a mob that crawls lies on the ground: hits are found on the body as the clients draw it
		BodyPose pose = pose(target).crawling(state, rules);
		Entity direct = source.getDirectEntity();
		Entity attacker = source.getEntity();

		if (direct instanceof AbstractArrow arrow) {
			Vec3 velocity = arrow.getDeltaMovement();
			if (velocity.lengthSqr() < 1e-6) velocity = target.getBoundingBox().getCenter().subtract(arrow.position());
			Vec3 dir = velocity.normalize();
			Vec3 origin = arrow.position().subtract(dir.scale(1.5));
			double length = velocity.length() + 4;
			Hit hit = HitLocator.cast(state, pose, origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, length);
			if (hit == null) {
				hit = HitLocator.castOrNearest(state, pose, origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, length);
			}
			return hit == null ? null : new Resolved(hit, arrow instanceof ThrownTrident ? WeaponKind.TRIDENT : WeaponKind.ARROW);
		}

		if (attacker instanceof LivingEntity living && direct == attacker) {
			Vec3 eye = living.getEyePosition();
			Vec3 dir;
			double reach;
			if (living instanceof Player player) {
				dir = player.getViewVector(1.0F);
				reach = player.entityInteractionRange() + 1.5;
			} else {
				ItemStack held = living.getMainHandItem();
				if (classify(held).edged()) {
					// a blade goes where the mob looks (zombies look you in the eyes): heads fly
					Vec3 toCenter = target.getBoundingBox().getCenter().subtract(eye).normalize();
					Vec3 look = living.getViewVector(1.0F);
					dir = look.dot(toCenter) > 0.85 ? look : toCenter;
				} else {
					// bare hands and teeth tear at the whole body
					double[] at = ru.deelter.chopchop.core.hit.MobAim.spread(java.util.concurrent.ThreadLocalRandom.current(),
							target.getBbWidth(), target.getBbHeight());
					dir = target.position().add(at[0], at[1], at[2]).subtract(eye).normalize();
				}
				reach = eye.distanceTo(target.getBoundingBox().getCenter()) + 2;
			}
			Hit hit = null;
			// a client with the mod says where it saw the blow land on the animated body; checked, never trusted blindly
			if (living instanceof net.minecraft.server.level.ServerPlayer player) {
				hit = ru.deelter.chopchop.core.hit.AimCheck.accept(state, pose, ChopServer.takeAim(player, target), eye.x, eye.y, eye.z,
						dir.x, dir.y, dir.z, reach);
			}
			if (hit == null) hit = HitLocator.castOrNearest(state, pose, eye.x, eye.y, eye.z, dir.x, dir.y, dir.z, reach);
			ItemStack weapon = source.getWeaponItem();
			if (weapon == null || weapon.isEmpty()) weapon = living.getMainHandItem();
			return hit == null ? null : new Resolved(hit, classify(weapon), sharpness(living, weapon));
		}

		if (!"coarse".equalsIgnoreCase(rules.getOtherSources())) return null;
		Vec3 center = target.getBoundingBox().getCenter();
		Vec3 from;
		if (source.is(DamageTypeTags.IS_FALL)) {
			double side = target.getRandom().nextBoolean() ? 0.12 : -0.12;
			Vec3 offset = Vec3.directionFromRotation(0, target.yBodyRot + 90).scale(side);
			from = target.position().add(offset).add(0, -1, 0);
			center = target.position().add(offset).add(0, 0.5, 0);
		} else if (source.getSourcePosition() != null) {
			from = source.getSourcePosition();
		} else {
			return null;
		}
		Vec3 dir = center.subtract(from);
		if (dir.lengthSqr() < 1e-6) return null;
		dir = dir.normalize();
		Hit hit = HitLocator.castOrNearest(state, pose, from.x, from.y, from.z, dir.x, dir.y, dir.z, from.distanceTo(center) + 3);
		return hit == null ? null : new Resolved(hit, WeaponKind.OTHER);
	}

	static int sharpness(LivingEntity holder, ItemStack weapon) {
		if (weapon == null || weapon.isEmpty()) return 0;
		var sharpness = holder.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.get(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS);
		return sharpness.map(h -> net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(h, weapon)).orElse(0);
	}

	static WeaponKind classify(ItemStack stack) {
		if (stack.is(ItemTags.SWORDS)) return WeaponKind.SWORD;
		if (stack.is(ItemTags.AXES)) return WeaponKind.AXE;
		if (stack.is(Items.TRIDENT) || stack.is(ItemTags.SPEARS)) return WeaponKind.TRIDENT;
		return WeaponKind.BLUNT;
	}
}
