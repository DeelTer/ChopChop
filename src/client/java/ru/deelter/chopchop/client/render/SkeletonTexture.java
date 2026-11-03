package ru.deelter.chopchop.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.core.profile.BodyProfile;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The texture of the bones inside a body (a vanilla skeleton's by default), and which of its texels are there: the
 * gaps between the ribs are see-through, and a wound shows meat there. Read once from the resource packs in use.
 */
public final class SkeletonTexture {
	private static final Map<String, SkeletonTexture> LOADED = new HashMap<>();

	private final Identifier id;
	private final int width, height;
	/**
	 * the texels that are there; null if the texture could not be read, then every texel counts
	 */
	private final boolean[] solid;

	private SkeletonTexture(Identifier id, int width, int height, boolean[] solid) {
		this.id = id;
		this.width = width;
		this.height = height;
		this.solid = solid;
	}

	/**
	 * The bones of a profile, null if it has none or the texture can't be had.
	 */
	public static SkeletonTexture of(BodyProfile profile) {
		String name = profile.getSkeletonTexture();
		if (name == null) return null;
		return LOADED.computeIfAbsent(name, n -> load(n, profile.getSkeletonTextureSize()));
	}

	/**
	 * After the resource packs change.
	 */
	public static void reset() {
		LOADED.clear();
	}

	private static SkeletonTexture load(String name, int[] size) {
		Identifier id = Identifier.tryParse(name);
		if (id == null) return null;
		int width = size != null && size.length == 2 ? size[0] : 64, height = size != null && size.length == 2 ? size[1] : 32;
		try {
			var resource = Minecraft.getInstance().getResourceManager().getResource(id);
			if (resource.isEmpty()) return new SkeletonTexture(id, width, height, null);
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				// a pack may have it at another resolution: the texels are found by their share of the texture
				boolean[] solid = new boolean[width * height];
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int px = x * image.getWidth() / width, py = y * image.getHeight() / height;
						solid[y * width + x] = (image.getPixel(px, py) >>> 24) > 16;
					}
				}
				return new SkeletonTexture(id, width, height, solid);
			}
		} catch (Exception e) {
			ChopChop.LOG.warn("Can't read the bones texture {}, bones are drawn without gaps", name, e);
			return new SkeletonTexture(id, width, height, null);
		}
	}

	public boolean solid(int u, int v) {
		if (u < 0 || v < 0 || u >= width || v >= height) return false;
		return solid == null || solid[v * width + u];
	}

	/**
	 * u0, v0, u1, v1 of a texel, 0 to 1
	 */
	public float[] uv(int u, int v) {
		return new float[]{(float) u / width, (float) v / height, (float) (u + 1) / width, (float) (v + 1) / height};
	}

	public RenderType type() {
		return RenderTypes.entityCutout(id);
	}

	public RenderType translucentType() {
		return RenderTypes.entityTranslucent(id);
	}
}
