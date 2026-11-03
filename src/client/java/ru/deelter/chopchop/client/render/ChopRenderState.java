package ru.deelter.chopchop.client.render;

import net.minecraft.client.model.Model;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * What ChopChop adds to an entity render state: the wounds to draw, taken when the state is extracted, and where
 * the draw happens, so a model drawn later from a deferred submit can still be matched to its entity.
 */
public interface ChopRenderState {
	WoundState chopchop$wounds();

	int chopchop$entityId();

	void chopchop$set(int entityId, WoundState wounds);

	Model<?> chopchop$mainModel();

	double[] chopchop$camera();

	void chopchop$setSubmit(Model<?> main, double camX, double camY, double camZ);
}
