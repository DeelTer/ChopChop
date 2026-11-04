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
	public static final Identifier SLIME = Identifier.fromNamespaceAndPath(ChopChop.ID, "textures/entity/slime_inside.png");

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
	private static final Sink.Buffer SLIME_QUADS = new Sink.Buffer();
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

	public static RenderType slimeType() {
		return RenderTypes.entityCutout(SLIME);
	}

	/**
	 * the texture of what is inside a part of this material
	 */
	public static Identifier insideTexture(String material) {
		return Material.goo(material) ? SLIME : Material.bleeds(material) ? MEAT : BONE;
	}

	public static RenderType insideType(String material) {
		return RenderTypes.entityCutout(insideTexture(material));
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
		SLIME_QUADS.clear();
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
		end(buffers, false);
	}

	/**
	 * @param blended the model was drawn with a blended (translucent) type: its buffers go to the transparency
	 *                (OIT) pass under Fabulous graphics, which has no pipelines for cutout types, so the gore is
	 *                written translucent too
	 */
	public static void end(Function<RenderType, VertexConsumer> buffers, boolean blended) {
		if (state != null) {
			try {
				MEAT_QUADS.flush(buffers, blended ? RenderTypes.entityTranslucent(MEAT) : meatType(), -1, light, overlay);
				BONE_QUADS.flush(buffers, blended ? RenderTypes.entityTranslucent(BONE) : boneType(), -1, light, overlay);
				SLIME_QUADS.flush(buffers, blended ? RenderTypes.entityTranslucent(SLIME) : slimeType(), -1, light, overlay);
				if (skeletonTexture != null) SKELETON_QUADS.flush(buffers, blended ? skeletonTexture.translucentType() : skeletonTexture.type(), -1, light, overlay);
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
		SLIME_QUADS.clear();
		SKELETON_QUADS.clear();
		skeletonTexture = null;
	}

	/**
	 * The spine under a head that came off: the middle 2 by 2 column of the bone of the part the head sat on (its
	 * parent, or the body), in that part's frame, which a head shares at rest. Null with no skeleton inside.
	 */
	private static BodyPart.Skeleton spine(WoundState wounds, BodyPart head) {
		if (skeletonTexture == null) return null;
		BodyProfile profile = wounds.getProfile();
		BodyPart under = head.getParent() != null ? profile.part(head.getParent()) : profile.part("body");
		BodyPart.Skeleton bone = under == null ? null : under.getSkeleton();
		if (bone == null || bone.box() == null || bone.box().length != 6) return null;
		float[] b = bone.box();
		float cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
		return new BodyPart.Skeleton(new float[]{cx - 1, b[1], cz - 1, cx + 1, b[4], cz + 1}, bone.cube(), bone.uv(), 0);
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

		// armour shows the wounds under it as holes (nothing inside of it), unless the player wants it whole
		boolean severedOnly = role == Role.ARMOR && !ClientConfig.get().isWoundsThroughArmor() || far;
		if (severedOnly && !partState.severed()) return false;
		VoxelMask mask = severedOnly ? (x, y, z) -> y >= partState.getSeveredAt() : partState::gone;
		VoxelMesher.Grid grid = VoxelMesher.Grid.of(cube, binding, part);
		VoxelMesher.skin(cube, grid, mask, pose, Sink.of(builder, color, lightCoords, overlayCoords));

		if (gore && !isOverlay && binding.first()) {
			light = lightCoords;
			overlay = overlayCoords;
			String material = part.getMaterial();
			Sink inside = Material.goo(material) ? SLIME_QUADS : Material.bleeds(material) ? MEAT_QUADS : BONE_QUADS;
			VoxelMesher.inside(grid, mask, material, pose, inside, part.getSkeleton(), skeletonTexture, SKELETON_QUADS);
			// boneScale 0: a body without bones (a spider, a slime), its stumps are meat only
			boolean bone = Material.bleeds(material) && ClientConfig.get().isBones() && part.getBoneScale() > 0;
			if (partState.getSeveredAt() == 0) {
				// the whole part is off, a head: the stump is where it sat, a neck of meat with the bone standing out
				VoxelMesher.neck(grid, grid.gy(), false, pose, inside);
				// one bone at a neck, on the body: the spine of the skeleton inside if there is one (the middle column
				// of the bone the head sat on), else a cartoon one. The head that flew off has none of its own
				var spine = spine(wounds, part);
				boolean real = bone && spine != null
						&& VoxelMesher.skeletonStump(grid, grid.gy(), false, spine, skeletonTexture, pose, SKELETON_QUADS);
				if (bone && !real) VoxelMesher.bone(grid, grid.gy(), part.getBoneScale(), false, pose, BONE_QUADS);
			} else if (partState.severed() && bone) {
				// a body with a skeleton inside shows its own bone in the stump, the others a cartoon one
				boolean real = skeletonTexture != null && part.getSkeleton() != null
						&& VoxelMesher.skeletonStump(grid, partState.getSeveredAt(), true, part.getSkeleton(), skeletonTexture, pose, SKELETON_QUADS);
				if (!real) VoxelMesher.bone(grid, partState.getSeveredAt(), part.getBoneScale(), true, pose, BONE_QUADS);
			}
		}
		return true;
	}
}
