package ru.deelter.chopchop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.Material;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.function.Function;

/**
 * The model draw in progress and the wounds it shows. Set right before a model is drawn and dropped right after,
 * see {@code ModelFeatureRendererMixin}; in between every cube of the model asks {@link #compile} whether it is drawn
 * the usual way. Render thread only.
 * <p>
 * Meat and bones use their own textures, so they can't go into the buffer of the skin: they are kept in
 * {@link Sink.Buffer}s and written into buffers of their own after the model, in the same feature pass.
 */
public final class GoreRender {
	public static final Identifier MEAT = Identifier.fromNamespaceAndPath(ChopChop.ID, "textures/entity/meat.png");
	public static final Identifier BONE = Identifier.fromNamespaceAndPath(ChopChop.ID, "textures/entity/bone.png");

	/**
	 * How a model drawn for a wounded entity shows the wounds.
	 */
	public enum Role {
		/**
		 * the body: holes, meat, bones
		 */
		MAIN,
		/**
		 * clothes and outer layers drawn by another model: holes, nothing inside
		 */
		LAYER,
		/**
		 * armor: only what is cut off goes, a cut on the skin does not dent the armor
		 */
		ARMOR
	}

	private static WoundState state;
	private static Role role;
	private static boolean gore;
	private static boolean far;
	private static int light, overlay;
	private static final Sink.Buffer MEAT_QUADS = new Sink.Buffer();
	private static final Sink.Buffer BONE_QUADS = new Sink.Buffer();
	/**
	 * the skeleton inside, drawn with its own texture
	 */
	private static final Sink.Buffer SKELETON_QUADS = new Sink.Buffer();
	private static SkeletonTexture skeletonTexture;

	private GoreRender() {
	}

	public static RenderType meatType() {
		return RenderTypes.entityCutout(MEAT);
	}

	public static RenderType boneType() {
		return RenderTypes.entityCutout(BONE);
	}

	/**
	 * @param withGore draw the inside too: not for translucent or outline draws
	 */
	public static void begin(WoundState wounds, Role drawRole, boolean withGore) {
		begin(wounds, drawRole, withGore, false);
	}

	/**
	 * @param distant far from the camera: only what is cut off is shown, small wounds are left out
	 */
	public static void begin(WoundState wounds, Role drawRole, boolean withGore, boolean distant) {
		far = distant;
		state = wounds;
		role = drawRole;
		gore = withGore && drawRole == Role.MAIN;
		MEAT_QUADS.clear();
		BONE_QUADS.clear();
		SKELETON_QUADS.clear();
		skeletonTexture = wounds == null || !ClientConfig.get().isSkeletonInside() ? null : SkeletonTexture.of(wounds.getProfile());
	}

	public static boolean active() {
		return state != null;
	}

	/**
	 * Ends the draw and writes the meat and bones it collected.
	 */
	public static void end(Function<RenderType, VertexConsumer> buffers) {
		if (state != null) {
			try {
				MEAT_QUADS.flush(buffers, meatType(), -1, light, overlay);
				BONE_QUADS.flush(buffers, boneType(), -1, light, overlay);
				if (skeletonTexture != null) SKELETON_QUADS.flush(buffers, skeletonTexture.type(), -1, light, overlay);
			} finally {
				reset();
			}
		}
	}

	public static void reset() {
		state = null;
		role = null;
		MEAT_QUADS.clear();
		BONE_QUADS.clear();
		SKELETON_QUADS.clear();
		skeletonTexture = null;
	}

	/**
	 * @return true when the cube was drawn here, false to draw it the usual way
	 */
	public static boolean compile(ModelPart.Cube cube, PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords, int color) {
		WoundState wounds = state;
		if (wounds == null) return false;
		Bindings.Binding binding = Bindings.of(cube);
		if (binding == null) return false;
		BodyProfile profile = wounds.getProfile();
		BodyPart part = profile.forModelPart(binding.name());
		if (part == null) return false;
		PartState partState = wounds.part(part.getIndex());
		if (partState.pristine()) return false;
		boolean isOverlay = !binding.name().equals(part.getName());

		boolean severedOnly = role == Role.ARMOR || far;
		if (severedOnly && !partState.severed()) return false;
		VoxelMask mask = severedOnly ? (x, y, z) -> y >= partState.getSeveredAt() : partState::gone;
		VoxelMesher.Grid grid = VoxelMesher.Grid.of(cube, binding, part);
		VoxelMesher.skin(cube, grid, mask, pose, Sink.of(builder, color, lightCoords, overlayCoords));

		if (gore && !isOverlay && binding.first()) {
			light = lightCoords;
			overlay = overlayCoords;
			String material = part.getMaterial();
			Sink inside = Material.bleeds(material) ? MEAT_QUADS : BONE_QUADS;
			VoxelMesher.inside(grid, mask, material, pose, inside, part.getSkeleton(), skeletonTexture, SKELETON_QUADS);
			boolean bone = Material.bleeds(material) && ClientConfig.get().isBones();
			if (partState.getSeveredAt() == 0) {
				// the whole part is off, a head: the stump is where it sat, a neck of meat with the bone standing out
				VoxelMesher.neck(grid, grid.gy(), false, pose, inside);
				if (bone) VoxelMesher.bone(grid, grid.gy(), part.getBoneScale(), false, pose, BONE_QUADS);
			} else if (partState.severed() && bone) {
				VoxelMesher.bone(grid, partState.getSeveredAt(), part.getBoneScale(), true, pose, BONE_QUADS);
			}
		}
		return true;
	}
}
