package ru.deelter.chopchop.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ru.deelter.chopchop.ChopChop;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The cartoon bone of {@code bone_connector.bbmodel}, read from its block model export. Elements with a rotation
 * about one axis are supported, as Blockbench writes them. Quads are in Blockbench pixels: the bone stands along +y
 * from y = 0, centered on x = z = 8.
 * <p>
 * A quad is 4 vertices of {@code x, y, z, u, v, nx, ny, nz}.
 */
public final class BoneMesh {
	private static final String PATH = "/assets/chopchop/geometry/bone_connector.json";
	private static final String[] FACES = {"north", "south", "west", "east", "up", "down"};
	private static List<float[]> quads = List.of();

	private BoneMesh() {
	}

	public static List<float[]> quads() {
		return quads;
	}

	public static void load() {
		try (InputStream in = BoneMesh.class.getResourceAsStream(PATH)) {
			if (in == null) throw new IllegalStateException("missing " + PATH);
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			List<float[]> list = new ArrayList<>();
			for (JsonElement element : root.getAsJsonArray("elements")) element(element.getAsJsonObject(), list);
			quads = List.copyOf(list);
		} catch (Exception e) {
			ChopChop.LOG.error("Can't read the bone model, stumps get no bone", e);
			quads = List.of();
		}
	}

	private static void element(JsonObject element, List<float[]> out) {
		float[] from = floats(element.getAsJsonArray("from"));
		float[] to = floats(element.getAsJsonArray("to"));
		Matrix4f rotation = new Matrix4f();
		if (element.has("rotation")) {
			JsonObject r = element.getAsJsonObject("rotation");
			float[] origin = floats(r.getAsJsonArray("origin"));
			float angle = (float) Math.toRadians(r.get("angle").getAsFloat());
			String axis = r.get("axis").getAsString();
			rotation.translate(origin[0], origin[1], origin[2]);
			switch (axis) {
				case "x" -> rotation.rotateX(angle);
				case "y" -> rotation.rotateY(angle);
				default -> rotation.rotateZ(angle);
			}
			rotation.translate(-origin[0], -origin[1], -origin[2]);
		}
		JsonObject faces = element.getAsJsonObject("faces");
		float x0 = from[0], y0 = from[1], z0 = from[2], x1 = to[0], y1 = to[1], z1 = to[2];
		for (String face : FACES) {
			if (!faces.has(face)) continue;
			float[] uv = floats(faces.getAsJsonObject(face).getAsJsonArray("uv"));
			// corners top left, top right, bottom right, bottom left as seen from outside
			float[][] c = switch (face) {
				case "north" -> new float[][]{{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}};
				case "south" -> new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}};
				case "west" -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}};
				case "east" -> new float[][]{{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}};
				case "up" -> new float[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
				default -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}};
			};
			float[][] tex = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
			Vector3f a = rotation.transformPosition(new Vector3f(c[0][0], c[0][1], c[0][2]));
			Vector3f b = rotation.transformPosition(new Vector3f(c[1][0], c[1][1], c[1][2]));
			Vector3f d = rotation.transformPosition(new Vector3f(c[3][0], c[3][1], c[3][2]));
			Vector3f normal = new Vector3f(d).sub(a).cross(new Vector3f(b).sub(a)).normalize();
			float[] quad = new float[32];
			for (int i = 0; i < 4; i++) {
				Vector3f p = rotation.transformPosition(new Vector3f(c[i][0], c[i][1], c[i][2]));
				int o = i * 8;
				quad[o] = p.x;
				quad[o + 1] = p.y;
				quad[o + 2] = p.z;
				// face uvs of a block model are in sixteenths of the texture
				quad[o + 3] = tex[i][0] / 16F;
				quad[o + 4] = tex[i][1] / 16F;
				quad[o + 5] = normal.x;
				quad[o + 6] = normal.y;
				quad[o + 7] = normal.z;
			}
			out.add(quad);
		}
	}

	private static float[] floats(JsonArray array) {
		float[] result = new float[array.size()];
		for (int i = 0; i < result.length; i++) result[i] = array.get(i).getAsFloat();
		return result;
	}
}
