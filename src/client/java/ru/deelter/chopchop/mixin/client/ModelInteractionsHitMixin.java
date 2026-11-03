package ru.deelter.chopchop.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.deelter.chopchop.client.compat.ModelInteractionsCompat;

import org.joml.Vector3f;

import java.util.function.Predicate;

/**
 * ModelInteractions' hit tests skip parts ChopChop cut off, see {@link ModelInteractionsCompat}. Applied only when
 * ModelInteractions is installed; nothing here is required.
 */
@Pseudo
@Mixin(targets = "dev.deelter.modelinteractions.interaction.HitTester", remap = false)
public abstract class ModelInteractionsHitMixin {
	@SuppressWarnings("rawtypes")
	@Inject(method = "raycast", at = @At("HEAD"), require = 0)
	private static void chopchop$ray(@Coerce Object rig, Vector3f origin, Vector3f dir, float maxDist, Predicate filter,
									 CallbackInfoReturnable<Object> cir) {
		ModelInteractionsCompat.begin(rig);
	}

	@SuppressWarnings("rawtypes")
	@Inject(method = "sphere", at = @At("HEAD"), require = 0)
	private static void chopchop$sphere(@Coerce Object rig, Vector3f center, float radius, Predicate filter,
										CallbackInfoReturnable<Object> cir) {
		ModelInteractionsCompat.begin(rig);
	}

	@SuppressWarnings({"rawtypes"})
	@ModifyVariable(method = {"raycast", "sphere"}, at = @At("HEAD"), argsOnly = true, require = 0)
	private static Predicate chopchop$filter(Predicate filter) {
		return ModelInteractionsCompat.wrap(filter);
	}
}
