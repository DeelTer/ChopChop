package ru.deelter.chopchop.server;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.function.BiPredicate;

/**
 * Where the public API reaches into the server: which damage ChopChop leaves alone for another mod to handle.
 */
public final class ChopApiHooks {
	private static volatile BiPredicate<LivingEntity, DamageSource> automatic = (target, source) -> true;

	private ChopApiHooks() {
	}

	public static void setAutomatic(BiPredicate<LivingEntity, DamageSource> filter) {
		automatic = filter == null ? (target, source) -> true : filter;
	}

	static boolean skipsAutomatic(LivingEntity target, DamageSource source) {
		try {
			return !automatic.test(target, source);
		} catch (RuntimeException e) {
			ru.deelter.chopchop.ChopChop.LOG.warn("The ChopChop damage filter of another mod threw", e);
			return false;
		}
	}
}
