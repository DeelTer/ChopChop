package ru.deelter.chopchop.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ru.deelter.chopchop.client.ClientWounds;
import ru.deelter.chopchop.client.Effects;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.Material;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;
import ru.deelter.chopchop.mixin.client.ModelPartAccessor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Pieces of bodies that came off: a copy of the cut segment, as it looked when it was cut, that falls, bounces and
 * goes away after a few seconds. A head is a show of its own: it is thrown up spinning, hops a few times with a
 * squish, and once it lies still it rolls over to sit upright and look at the body it came from. Cosmetic only and
 * local to each client, nothing is synced.
 * <p>
 * Simulated per tick, drawn interpolated, so a camera can ride along smoothly ({@link HeadCam}).
 */
public final class Debris {
	private static final int MAX_PIECES = 48;
	private static final double GRAVITY = 0.045;
	private static final List<Chunk> CHUNKS = new ArrayList<>();

	private Debris() {
	}

	/**
	 * One cube of the piece, with where its part was in the world when it was cut.
	 */
	private record Piece(ModelPart.Cube cube, Bindings.Binding binding, Matrix4f world) {
	}

	public static final class Chunk {
		final RenderType skin;
		final BodyPart part;
		final VoxelMask mask;
		final int cut;
		final List<Piece> pieces;
		final Vector3f pivot;
		final boolean head;
		/**
		 * where the body stood, for a head to turn to
		 */
		final Vec3 body;
		/**
		 * rotation of the part at the cut, for working out how the head sits upright
		 */
		final Quaternionf start;
		final float size;
		final Vector3f offset = new Vector3f();
		final Vector3f lastOffset = new Vector3f();
		final Vector3f velocity;
		final Vector3f spinAxis;
		final Quaternionf rotation = new Quaternionf();
		final Quaternionf lastRotation = new Quaternionf();
		float spin;
		int age;
		int light;
		int bounces;
		boolean resting;
		boolean kept;

		Chunk(RenderType skin, BodyPart part, VoxelMask mask, int cut, List<Piece> pieces, Vector3f pivot, Vec3 body, float size,
			  boolean flung, Vec3 away, RandomSource random) {
			this.skin = skin;
			this.part = part;
			this.mask = mask;
			this.cut = cut;
			this.pieces = pieces;
			this.pivot = pivot;
			this.body = body;
			this.size = size;
			this.head = cut == 0 && "head".equals(part.getName());
			this.start = pieces.getFirst().world().getNormalizedRotation(new Quaternionf());
			float up = head ? 0.32F + random.nextFloat() * 0.08F : 0.12F + random.nextFloat() * 0.08F;
			// a head goes mostly up and lands near its body, both in one picture
			float side = head ? 0.05F : 0.06F;
			this.velocity = new Vector3f((float) random.nextGaussian() * side, up, (float) random.nextGaussian() * side);
			this.spinAxis = new Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F).normalize();
			this.spin = head ? 0.45F + random.nextFloat() * 0.25F : 0.15F + random.nextFloat() * 0.25F;
			if (flung) {
				// a blast throws it out from the body, high and spinning
				// away from where the blast met it, or out from the body; a little off, never twice the same
				Vec3 from = away != null ? away : body;
				Vector3f out = new Vector3f((float) (pivot.x - from.x), 0, (float) (pivot.z - from.z));
				if (out.lengthSquared() < 1e-4F) out.set(random.nextFloat() - 0.5F, 0, random.nextFloat() - 0.5F);
				out.normalize().add((random.nextFloat() - 0.5F) * 0.5F, 0, (random.nextFloat() - 0.5F) * 0.5F)
						.normalize(0.3F + random.nextFloat() * 0.25F);
				velocity.set(out.x, (head ? 0.45F : 0.35F) + random.nextFloat() * 0.2F, out.z);
				spin = 0.6F + random.nextFloat() * 0.4F;
			}
		}

		public boolean isHead() {
			return head;
		}

		public void keep(boolean keep) {
			kept = keep;
		}

		/**
		 * Where the piece is now, between the last two ticks.
		 */
		public Vector3f position(float partial) {
			return new Vector3f(lastOffset).lerp(offset, partial).add(pivot);
		}

		/**
		 * The rotation of the piece in the world now: what it turned since the cut, on top of how it was at the cut.
		 */
		public Quaternionf orientation(float partial) {
			return new Quaternionf(lastRotation).slerp(rotation, partial).mul(start);
		}

		public float size() {
			return size;
		}
	}

	public static void clear() {
		CHUNKS.clear();
		HeadCam.stop();
		KillCam.stop();
	}

	/**
	 * Called while the main model of an entity is posed for its draw: turns the cuts noted for it into pieces.
	 *
	 * @param pose   the model's pose, relative to the camera
	 * @param entity where the entity stands
	 */
	public static void capture(Model<?> model, WoundState state, int entityId, PoseStack.Pose pose, RenderType skin, double[] camera,
							   Vec3 entity) {
		List<ClientWounds.Detach> detaches = ClientWounds.takeDetaches(entityId);
		if (detaches.isEmpty() || !ClientConfig.get().isDebris()) return;
		Bindings.ensure(model);
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		for (ClientWounds.Detach detach : detaches) {
			if (detach.part() >= state.partCount()) continue;
			BodyPart part = state.getProfile().part(detach.part());
			PartState before = detach.before() != null ? detach.before() : new PartState(part);
			int cut = detach.cut();
			VoxelMask mask = (x, y, z) -> y < cut || before.gone(x, y, z);
			List<Piece> pieces = new ArrayList<>();
			List<String> names = new ArrayList<>();
			names.add(part.getName());
			names.addAll(part.getOverlays());
			for (String name : names) {
				List<ModelPart> path = Bindings.path(model, name);
				if (path == null || path.isEmpty() || !path.getLast().visible) continue;
				PoseStack stack = new PoseStack();
				stack.last().set(pose);
				for (ModelPart p : path) p.translateAndRotate(stack);
				Matrix4f world = new Matrix4f().translation((float) camera[0], (float) camera[1], (float) camera[2]).mul(stack.last().pose());
				for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) path.getLast()).chopchop$cubes()) {
					Bindings.Binding binding = Bindings.of(cube);
					if (binding != null) pieces.add(new Piece(cube, binding, world));
				}
			}
			if (pieces.isEmpty()) continue;
			Piece first = pieces.getFirst();
			VoxelMesher.Grid grid = VoxelMesher.Grid.of(first.cube(), first.binding(), part);
			Vector3f center = first.world().transformPosition(new Vector3f(
					(grid.ox() + grid.sx() * grid.gx() / 2F) / 16F,
					(grid.oy() + grid.sy() * (cut + grid.gy()) / 2F) / 16F,
					(grid.oz() + grid.sz() * grid.gz() / 2F) / 16F));
			float size = first.world().getScale(new Vector3f()).x * Math.max(grid.sx() * grid.gx(), grid.sz() * grid.gz()) / 16F;
			// the oldest piece goes first, never a head with a camera in it
			if (CHUNKS.size() >= MAX_PIECES) {
				for (Iterator<Chunk> it = CHUNKS.iterator(); it.hasNext(); ) {
					if (!it.next().kept) {
						it.remove();
						break;
					}
				}
			}
			Chunk chunk = new Chunk(skin, part, mask, cut, pieces, center, entity, size, detach.flung(), detach.away(), level.getRandom());
			CHUNKS.add(chunk);
			if (chunk.head && Minecraft.getInstance().player != null && entityId == Minecraft.getInstance().player.getId()) {
				HeadCam.follow(chunk);
			} else if (chunk.head) {
				KillCam.offer(chunk, entityId);
			}
		}
	}

	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.isPaused()) {
			if (level == null) clear();
			return;
		}
		int lifetime = ClientConfig.get().getDebrisSeconds() * 20;
		Iterator<Chunk> it = CHUNKS.iterator();
		while (it.hasNext()) {
			Chunk c = it.next();
			c.lastOffset.set(c.offset);
			c.lastRotation.set(c.rotation);
			if (++c.age > (c.head ? lifetime * 2 : lifetime) && !c.kept) {
				it.remove();
				continue;
			}
			// a head rests on its side, not on its center
			float rest = c.head ? c.size / 2F : 0.06F;
			Vec3 at = new Vec3(c.pivot.x + c.offset.x, c.pivot.y + c.offset.y, c.pivot.z + c.offset.z);
			if (!c.resting) {
				c.velocity.y -= (float) GRAVITY;
				c.velocity.mul(0.98F);
				Vec3 next = at.add(c.velocity.x, c.velocity.y, c.velocity.z);
				BlockPos below = BlockPos.containing(next.x, next.y - rest, next.z);
				boolean ground = !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
				if (ground && c.velocity.y < 0) {
					boolean bounce = c.velocity.y < -0.12F;
					c.velocity.y = bounce ? -c.velocity.y * (c.head ? 0.5F : 0.3F) : 0;
					c.velocity.x *= 0.6F;
					c.velocity.z *= 0.6F;
					c.spin *= 0.5F;
					if (bounce && c.head) Effects.bounce(at, c.bounces++);
					// sit on the top of the block below, not in it
					double top = below.getY() + level.getBlockState(below).getCollisionShape(level, below).max(net.minecraft.core.Direction.Axis.Y);
					if (!bounce) c.offset.y = (float) (top + rest - c.pivot.y);
					if (c.velocity.y == 0 && c.velocity.lengthSquared() < 1e-4) c.resting = true;
				}
				BlockPos inside = BlockPos.containing(next.x, next.y, next.z);
				if (!level.getBlockState(inside).getCollisionShape(level, inside).isEmpty()) {
					c.velocity.x = 0;
					c.velocity.z = 0;
				}
				if (!c.resting) c.offset.add(c.velocity);
				c.rotation.premul(new Quaternionf().rotationAxis(c.spin, c.spinAxis));
			}
			if (c.head && (c.resting || c.bounces > 0 && c.velocity.lengthSquared() < 0.02F)) settle(c);
			BlockPos pos = BlockPos.containing(at.x, at.y + 0.1, at.z);
			c.light = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
		}
	}

	/**
	 * A head on the ground rolls over to sit upright and face its body. Upright facing yaw {@code φ} is the rotation
	 * the game gives a body standing that way: {@code Ry(180 - φ)} after the model's flip, {@code Rz(180)}.
	 */
	private static void settle(Chunk c) {
		double dx = c.body.x - (c.pivot.x + c.offset.x);
		double dz = c.body.z - (c.pivot.z + c.offset.z);
		if (dx * dx + dz * dz < 1e-4) dz = 1;
		float yaw = (float) Mth.atan2(-dx, dz);
		Quaternionf upright = new Quaternionf().rotationY((float) Math.PI - yaw).rotateZ((float) Math.PI);
		Quaternionf target = upright.mul(new Quaternionf(c.start).conjugate());
		c.rotation.slerp(target, 0.18F);
		c.spin = 0;
	}

	public static void submit(LevelRenderContext context) {
		if (CHUNKS.isEmpty()) return;
		Vec3 camera = context.levelState().cameraRenderState.pos;
		if (camera == null) return;
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack stack = context.poseStack();
		boolean bones = ClientConfig.get().isBones();
		int lifetime = ClientConfig.get().getDebrisSeconds() * 20;
		for (Chunk c : CHUNKS) {
			int left = (c.head ? lifetime * 2 : lifetime) - c.age;
			float shrink = c.kept ? 1F : Math.min(1F, left / 10F);
			Vector3f at = new Vector3f(c.lastOffset).lerp(c.offset, partial);
			Quaternionf turn = new Quaternionf(c.lastRotation).slerp(c.rotation, partial);
			Matrix4f move = new Matrix4f()
					.translation((float) (c.pivot.x + at.x - camera.x), (float) (c.pivot.y + at.y - camera.y), (float) (c.pivot.z + at.z - camera.z))
					.rotate(turn)
					.scale(Math.max(0.01F, shrink))
					.translate(-c.pivot.x, -c.pivot.y, -c.pivot.z);
			for (Piece piece : c.pieces) {
				stack.pushPose();
				stack.mulPose(new Matrix4f(move).mul(piece.world()));
				VoxelMesher.Grid grid = VoxelMesher.Grid.of(piece.cube(), piece.binding(), c.part);
				int light = c.light;
				var collector = context.submitNodeCollector();
				collector.submitCustomGeometry(stack, c.skin,
						(pose, consumer) -> VoxelMesher.skin(piece.cube(), grid, c.mask, pose, Sink.of(consumer, -1, light, OverlayTexture.NO_OVERLAY)));
				if (piece == c.pieces.getFirst()) {
					String material = c.part.getMaterial();
					boolean meat = Material.bleeds(material);
					RenderType inside = meat ? GoreRender.meatType() : GoreRender.boneType();
					collector.submitCustomGeometry(stack, inside, (pose, consumer) -> {
						Sink sink = Sink.of(consumer, -1, light, OverlayTexture.NO_OVERLAY);
						VoxelMesher.inside(grid, c.mask, material, pose, sink);
						// a head has its neck at the bottom
						if (c.cut == 0) VoxelMesher.neck(grid, grid.gy(), true, pose, sink);
					});
					if (meat && bones) {
						int row = c.cut == 0 ? grid.gy() : c.cut;
						collector.submitCustomGeometry(stack, GoreRender.boneType(),
								(pose, consumer) -> VoxelMesher.bone(grid, row, c.part.getBoneScale(), c.cut == 0, pose,
										Sink.of(consumer, -1, light, OverlayTexture.NO_OVERLAY)));
					}
				}
				stack.popPose();
			}
		}
	}

	public static int size() {
		return CHUNKS.size();
	}
}
