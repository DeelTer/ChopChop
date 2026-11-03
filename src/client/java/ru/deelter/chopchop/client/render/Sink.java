package ru.deelter.chopchop.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;

import java.util.function.Function;

/**
 * Where vertices go: straight into a buffer, or kept until the buffer of another texture can be had.
 */
public interface Sink {
	/**
	 * Position and normal already transformed.
	 *
	 * @param shade multiplies the colour, 1 as it is: meat deep in a wound is darker
	 */
	void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, float shade);

	default void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		vertex(x, y, z, u, v, nx, ny, nz, 1F);
	}

	/**
	 * The colour with its red, green and blue times {@code shade}, alpha as it is.
	 */
	static int shaded(int color, float shade) {
		if (shade >= 1F) return color;
		int r = Math.round(((color >> 16) & 0xFF) * shade);
		int g = Math.round(((color >> 8) & 0xFF) * shade);
		int b = Math.round((color & 0xFF) * shade);
		return (color & 0xFF000000) | r << 16 | g << 8 | b;
	}

	static Sink of(VertexConsumer consumer, int color, int light, int overlay) {
		return (x, y, z, u, v, nx, ny, nz, shade) -> consumer.addVertex(x, y, z, shaded(color, shade), u, v, overlay, light, nx, ny, nz);
	}

	/**
	 * Keeps vertices for later, see {@link #flush}.
	 */
	final class Buffer implements Sink {
		private static final int STRIDE = 9;
		private final FloatArrayList data = new FloatArrayList();

		@Override
		public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz, float shade) {
			data.add(x);
			data.add(y);
			data.add(z);
			data.add(u);
			data.add(v);
			data.add(nx);
			data.add(ny);
			data.add(nz);
			data.add(shade);
		}

		public boolean isEmpty() {
			return data.isEmpty();
		}

		public void clear() {
			data.clear();
		}

		public <T> void flush(Function<T, VertexConsumer> buffers, T type, int color, int light, int overlay) {
			if (data.isEmpty()) return;
			VertexConsumer consumer = buffers.apply(type);
			float[] d = data.elements();
			for (int i = 0; i + STRIDE - 1 < data.size(); i += STRIDE) {
				consumer.addVertex(d[i], d[i + 1], d[i + 2], shaded(color, d[i + 8]), d[i + 3], d[i + 4], overlay, light, d[i + 5], d[i + 6], d[i + 7]);
			}
			data.clear();
		}
	}
}
