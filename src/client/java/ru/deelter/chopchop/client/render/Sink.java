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
	 */
	void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz);

	static Sink of(VertexConsumer consumer, int color, int light, int overlay) {
		return (x, y, z, u, v, nx, ny, nz) -> consumer.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz);
	}

	/**
	 * Keeps vertices for later, see {@link #flush}.
	 */
	final class Buffer implements Sink {
		private final FloatArrayList data = new FloatArrayList();

		@Override
		public void vertex(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
			data.add(x);
			data.add(y);
			data.add(z);
			data.add(u);
			data.add(v);
			data.add(nx);
			data.add(ny);
			data.add(nz);
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
			for (int i = 0; i + 7 < data.size(); i += 8) {
				consumer.addVertex(d[i], d[i + 1], d[i + 2], color, d[i + 3], d[i + 4], overlay, light, d[i + 5], d[i + 6], d[i + 7]);
			}
			data.clear();
		}
	}
}
