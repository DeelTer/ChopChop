package ru.deelter.chopchop.client.render;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;

import java.util.List;

/**
 * How a model is posed as a dead body, on top of its own animation. Called right after the model was posed for its
 * draw; the next draw poses it afresh, so nothing stays on the model.
 */
public final class Corpse {
	private static final String[] ARMS = {"right_arm", "left_arm"};
	/**
	 * arms lie along the body, a little away from it
	 */
	private static final float SPREAD = 0.08F;

	private Corpse() {
	}

	public static void lowerArms(Model<?> model) {
		Bindings.ensure(model);
		for (int i = 0; i < ARMS.length; i++) {
			List<ModelPart> path = Bindings.path(model, ARMS[i]);
			if (path == null || path.isEmpty()) continue;
			ModelPart arm = path.getLast();
			arm.xRot = 0;
			arm.yRot = 0;
			// the model's right arm is on its -x side: away from the body is +z rotation for it, -z for the left
			arm.zRot = i == 0 ? SPREAD : -SPREAD;
		}
	}
}
