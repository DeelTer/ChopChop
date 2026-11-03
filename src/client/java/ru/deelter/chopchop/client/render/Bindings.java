package ru.deelter.chopchop.client.render;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import ru.deelter.chopchop.mixin.client.ModelPartAccessor;

import java.util.*;

/**
 * Which model part each cube of a model belongs to, by name, and the chain of parts from the root to it. Built
 * once per model instance; models live as long as their renderer.
 * <p>
 * Names are all a profile knows of a model: the renderer adapter of a profile is the set of names its parts and
 * overlays use. A model without any of them is simply not touched.
 */
public final class Bindings {
	private static final Map<ModelPart.Cube, Binding> CUBES = new WeakHashMap<>();
	private static final Map<Model<?>, Map<String, List<ModelPart>>> PATHS = new WeakHashMap<>();

	/**
	 * @param name   name of the model part the cube is in
	 * @param single the part has just this one cube: voxels map onto the cube itself, not the profile box
	 * @param first  the first cube of its part: gore of the part is drawn with it
	 */
	public record Binding(String name, boolean single, boolean first) {
	}

	private Bindings() {
	}

	public static Binding of(ModelPart.Cube cube) {
		return CUBES.get(cube);
	}

	public static void ensure(Model<?> model) {
		if (PATHS.containsKey(model)) return;
		Map<String, List<ModelPart>> paths = new HashMap<>();
		walk(model.root(), "root", new ArrayList<>(), paths);
		PATHS.put(model, paths);
	}

	/**
	 * Parts from the root down to the named one, the root included: it has a pose of its own.
	 */
	public static List<ModelPart> path(Model<?> model, String name) {
		Map<String, List<ModelPart>> paths = PATHS.get(model);
		return paths == null ? null : paths.get(name);
	}

	private static void walk(ModelPart part, String name, List<ModelPart> chain, Map<String, List<ModelPart>> paths) {
		chain.add(part);
		paths.putIfAbsent(name, List.copyOf(chain));
		List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).chopchop$cubes();
		for (int i = 0; i < cubes.size(); i++) {
			CUBES.putIfAbsent(cubes.get(i), new Binding(name, cubes.size() == 1, i == 0));
		}
		for (Map.Entry<String, ModelPart> child : ((ModelPartAccessor) (Object) part).chopchop$children().entrySet()) {
			walk(child.getValue(), child.getKey(), chain, paths);
		}
		chain.removeLast();
	}
}
