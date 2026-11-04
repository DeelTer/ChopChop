package ru.deelter.chopchop.client.cpm;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.Cube;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.model.RootModelElement;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.client.render.GoreRender;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;
import ru.deelter.chopchop.mixin.cpm.ModelDefinitionAccessor;

import java.util.List;
import java.util.UUID;

/**
 * Wounds on a model of Customizable Player Models. Such a model is made of cubes of its own, drawn by CPM and not as
 * cubes of the game, so the holes of small wounds can't be cut into it. What is cut off goes: every element of the
 * part below the cut is hidden for the draw (the whole part for a head), and the meat and bone of the stump are added
 * after the model ({@link GoreRender#stumps}). Where the model shows the part of the game itself, that part gets its
 * holes as usual.
 * <p>
 * The wounds are the ones of the draw in progress, so the copy of a dead player and the first person arm get theirs.
 * Render thread only; only loaded when CPM is installed.
 */
public final class CpmWounds {
	private CpmWounds() {
	}

	public static void apply(ModelDefinition def, UUID player) {
		WoundState wounds = GoreRender.drawing();
		if (wounds == null) return;
		GoreRender.ownModel();
		try {
			for (RenderedCube cube : ((ModelDefinitionAccessor) def).chopchop$cubes()) {
				if (!(cube instanceof RootModelElement root) || !(root.getPart() instanceof PlayerModelParts p) || p == PlayerModelParts.CUSTOM_PART) continue;
				BodyPart part = wounds.getProfile().part(p.getName());
				if (part == null) continue;
				PartState state = wounds.part(part.getIndex());
				if (!state.severed()) continue;
				if (state.getSeveredAt() == 0) {
					hideAll(root);
					continue;
				}
				float[] b = part.getBox();
				float cut = b[1] + (b[4] - b[1]) * state.getSeveredAt() / part.gy();
				hideBelow(root.children, 0, cut);
			}
		} catch (RuntimeException e) {
			ChopChop.LOG.debug("Can't cut a CPM model", e);
		}
	}

	private static void hideAll(RenderedCube cube) {
		cube.setVisible(false);
		if (cube.children != null) for (RenderedCube child : cube.children) hideAll(child);
	}

	/**
	 * Hides the elements that sit below the cut, in the frame of the part: their position along y, the rotations
	 * between them left out. A rough cut, as good as the elements follow the limb.
	 *
	 * @param y where the pivot of these elements is, pixels down the part
	 */
	private static void hideBelow(List<RenderedCube> cubes, float y, float cut) {
		if (cubes == null) return;
		for (RenderedCube c : cubes) {
			Vec3f pos = c.pos;
			float pivot = y + (pos == null ? 0 : pos.y);
			Cube def = c.getCube();
			float middle = pivot;
			if (def != null && def.offset != null && def.size != null) middle += def.offset.y + def.size.y / 2;
			if (middle >= cut) {
				hideAll(c);
				continue;
			}
			hideBelow(c.children, pivot, cut);
		}
	}
}
