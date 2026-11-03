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

	/**
	 * A dead body lying on the ground: direction of the fall in the model's turned frame (x, z), how far it tipped
	 * (radians), the roll, how far it sank (0 to 1). Null for a living entity.
	 */
	float[] chopchop$corpse();

	void chopchop$setCorpse(float[] corpse);

	/**
	 * the skin of the entity, for pieces that fade out
	 */
	net.minecraft.resources.Identifier chopchop$texture();

	void chopchop$setTexture(net.minecraft.resources.Identifier texture);
}
