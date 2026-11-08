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
	/**
	 * parts whose inside this draw already holds, so a model of Customizable Player Models gets the stumps it does not
	 * draw itself
	 */
	private static final java.util.BitSet GORED = new java.util.BitSet();
	/**
	 * the model being drawn is one of Customizable Player Models, see {@link #ownModel}
	 */
	private static boolean ownModel;
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
		begin(wounds, drawRole, withGore, distant, 0);
	}

	/**
	 * @param distanceSq squared distance to the camera: far off the bones inside are not looked up
	 */
	public static void begin(WoundState wounds, Role drawRole, boolean withGore, boolean distant, double distanceSq) {
		far = distant;
		state = wounds;
		role = drawRole;
		gore = withGore && drawRole == Role.MAIN;
		MEAT_QUADS.clear();
		BONE_QUADS.clear();
		SLIME_QUADS.clear();
		GORED.clear();
		ownModel = false;
		SKELETON_QUADS.clear();
		int bonesUpTo = ClientConfig.get().getSkeletonDistance();
		skeletonTexture = wounds == null || !ClientConfig.get().isSkeletonInside() || distanceSq > (double) bonesUpTo * bonesUpTo
				? null : SkeletonTexture.of(wounds.getProfile());
	}

	/**
	 * The wounds of the model being drawn as the body (not its armour or layers), null when none.
	 */
	/**
	 * The model being drawn is not made of the cubes of the game but of its own (Customizable Player Models): the
	 * stumps it does not draw are added after it, see {@link #stumps}.
	 */
	public static void ownModel() {
		ownModel = true;
	}

	public static WoundState drawing() {
		return role == Role.MAIN ? state : null;
	}

	/**
	 * After a model of Customizable Player Models was drawn (its own cubes, not the ones of the game, where the
	 * stumps come from): the meat and bone of every part that is cut off and got none, in the frame of the part as
	 * the model was posed.
	 *
	 * @param pose the pose the model was drawn with
	 */
	public static void stumps(net.minecraft.client.model.Model<?> model, PoseStack.Pose pose, int lightCoords, int overlayCoords) {
		WoundState wounds = state;
		if (wounds == null || !gore || !ownModel) return;
		light = lightCoords;
		overlay = overlayCoords;
		for (int i = 0; i < wounds.partCount(); i++) {
			PartState partState = wounds.part(i);
			if (!partState.severed() || GORED.get(i)) continue;
			BodyPart part = partState.getPart();
			var path = Bindings.path(model, part.getName());
			if (path == null || path.isEmpty()) continue;
			PoseStack stack = new PoseStack();
			stack.last().set(pose);
			for (ModelPart p : path) p.translateAndRotate(stack);
			float[] b = part.getBox();
			VoxelMesher.Grid grid = new VoxelMesher.Grid(b[0], b[1], b[2], (b[3] - b[0]) / part.gx(), (b[4] - b[1]) / part.gy(),
					(b[5] - b[2]) / part.gz(), part.gx(), part.gy(), part.gz());
			GORED.set(i);
			// the inside of the cut only: the wounds of the skin of a model of its own are not known
			inside(wounds, part, partState, grid, (x, y, z) -> y >= partState.getSeveredAt(), stack.last());
		}
	}

	public static boolean active() {
		return state != null;
	}

	/**
	 * render types are few and live for the game: what each one is is worked out once, not with a string every frame
	 */
	private static final java.util.Map<RenderType, Boolean> ARMOR = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/**
	 * The type draws armour (its name says so).
	 */
	public static boolean isArmor(RenderType type) {
		return ARMOR.computeIfAbsent(type, t -> t.toString().contains("armor"));
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
			GORED.set(part.getIndex());
			inside(wounds, part, partState, grid, mask, pose);
		}
		return true;
	}

	/**
	 * The voxel of the part a whole part was cut off from, where that part sat on it, is gone too: the body was
	 * cleaved down the middle or cut in two at the waist, and the stump went with the half that fell.
	 */
	private static boolean socketGone(WoundState wounds, BodyPart part) {
		BodyProfile profile = wounds.getProfile();
		BodyPart parent = part.getParent() != null ? profile.part(part.getParent()) : part.getName().equals("body") ? null : profile.part("body");
		if (parent == null || parent == part || parent.getIndex() >= wounds.partCount()) return false;
		PartState on = wounds.part(parent.getIndex());
		if (on.pristine()) return false;
		// where the part sat: the middle of its first row, the one at the joint
		var at = ru.deelter.chopchop.core.hit.HitLocator.voxelCenter(profile, part, part.gx() / 2, 0, part.gz() / 2);
		double best = Double.MAX_VALUE;
		boolean gone = false;
		for (int x = 0; x < parent.gx(); x++) for (int y = 0; y < parent.gy(); y++) for (int z = 0; z < parent.gz(); z++) {
			double d = ru.deelter.chopchop.core.hit.HitLocator.voxelCenter(profile, parent, x, y, z).distanceSquared(at);
			if (d < best) {
				best = d;
				gone = on.gone(x, y, z);
			}
		}
		return gone;
	}

	/**
	 * The inside of a part: meat (or bone, or goo) wherever it is open, and at a stump its bone.
	 */
	private static void inside(WoundState wounds, BodyPart part, PartState partState, VoxelMesher.Grid grid, VoxelMask mask, PoseStack.Pose pose) {
		{
			String material = part.getMaterial();
			Sink inside = Material.goo(material) ? SLIME_QUADS : Material.bleeds(material) ? MEAT_QUADS : BONE_QUADS;
			VoxelMesher.inside(grid, mask, material, pose, inside, part.getSkeleton(), skeletonTexture, SKELETON_QUADS);
			// boneScale 0: a body without bones (a spider, a slime), its stumps are meat only
			boolean bone = Material.bleeds(material) && ClientConfig.get().isBones() && part.getBoneScale() > 0;
			if (partState.getSeveredAt() == 0 && socketGone(wounds, part)) {
				// the part it sat on went with it (a body cleaved or cut in two): no stump left in the air
			} else if (partState.getSeveredAt() == 0 && !part.isHead()) {
				// a whole limb off: the stump is at its root (row 0, the shoulder or the hip), not at its far end where
				// the hand or the foot was; meat over the joint and the bone standing out of it, as a limb cut lower
				VoxelMesher.neck(grid, 0, true, pose, inside);
				if (bone) {
					boolean real = skeletonTexture != null && part.getSkeleton() != null
							&& VoxelMesher.skeletonStump(grid, 0, true, part.getSkeleton(), skeletonTexture, pose, SKELETON_QUADS);
					if (!real) VoxelMesher.bone(grid, 0, part.getBoneScale(), true, pose, BONE_QUADS);
				}
			} else if (partState.getSeveredAt() == 0) {
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
	}
}
