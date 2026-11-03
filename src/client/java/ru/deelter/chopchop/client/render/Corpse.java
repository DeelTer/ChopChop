package ru.deelter.chopchop.client.render;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import ru.deelter.chopchop.client.Corpses;

import java.util.List;

/**
 * How a model is posed as a dead body, on top of its own animation: arms let down along the body (a zombie's too, on
 * its back they would stick up), then every joint as limp as {@link Corpses} has it swing. Called right after the
 * model was posed for its draw, for the body and its armour and layers alike; the next draw poses it afresh.
 */
public final class Corpse {
	private static final int OFFSET = 6;

	private Corpse() {
	}

	/**
	 * @param corpse the corpse values of the render state, joints from index 6
	 */
	public static void pose(Model<?> model, float[] corpse) {
		Bindings.ensure(model);
		float[] j = new float[Corpses.JOINTS];
		if (corpse.length >= OFFSET + Corpses.JOINTS) System.arraycopy(corpse, OFFSET, j, 0, Corpses.JOINTS);
		set(model, "right_arm", j[Corpses.RIGHT_ARM_X], 0, 0.08F + j[Corpses.RIGHT_ARM_Z]);
		set(model, "left_arm", j[Corpses.LEFT_ARM_X], 0, -0.08F + j[Corpses.LEFT_ARM_Z]);
		set(model, "right_leg", j[Corpses.RIGHT_LEG_X], 0, j[Corpses.RIGHT_LEG_Z]);
		set(model, "left_leg", j[Corpses.LEFT_LEG_X], 0, j[Corpses.LEFT_LEG_Z]);
		// four legs: the front pair like arms hanging, the hind pair like legs
		set(model, "right_front_leg", j[Corpses.RIGHT_ARM_X] * 0.6F, 0, j[Corpses.RIGHT_ARM_Z] * 0.5F);
		set(model, "left_front_leg", j[Corpses.LEFT_ARM_X] * 0.6F, 0, j[Corpses.LEFT_ARM_Z] * 0.5F);
		set(model, "right_hind_leg", j[Corpses.RIGHT_LEG_X], 0, j[Corpses.RIGHT_LEG_Z]);
		set(model, "left_hind_leg", j[Corpses.LEFT_LEG_X], 0, j[Corpses.LEFT_LEG_Z]);
		set(model, "head", j[Corpses.HEAD_PITCH], j[Corpses.HEAD_YAW], 0);
	}

	private static void set(Model<?> model, String name, float x, float y, float z) {
		List<ModelPart> path = Bindings.path(model, name);
		if (path == null || path.isEmpty()) return;
		ModelPart part = path.getLast();
		part.xRot = x;
		part.yRot = y;
		part.zRot = z;
	}
}
