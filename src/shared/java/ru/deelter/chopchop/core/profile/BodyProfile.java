package ru.deelter.chopchop.core.profile;

import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A body layout: which parts an entity has, where they are at rest, which of them come off and what is inside.
 * One file in {@code chopchop/profiles/}, see {@link Profiles}.
 */
@Getter
public final class BodyProfile {
	private String id;
	/**
	 * entity types that use it, "minecraft:zombie"
	 */
	private List<String> entities = List.of();
	/**
	 * "meat" or "bone": what a wound shows and which particles come out
	 */
	private String material = Material.MEAT;
	/**
	 * height of the model at rest in model units, to scale it to the box of an entity of another size
	 */
	private float height = 32;
	/**
	 * How the body lies once it has no legs, see {@link Crawl}. Null: it does not crawl, it just stays as it is
	 */
	private Crawl crawl;
	private List<BodyPart> parts = List.of();
	/**
	 * the texture the bones of {@link BodyPart#getSkeleton} are drawn with, and its size in pixels
	 */
	private String skeletonTexture = "minecraft:textures/entity/skeleton/skeleton.png";
	private int[] skeletonTextureSize = {64, 32};

	private transient Map<String, BodyPart> byName;
	/**
	 * model part name, of a part or of an overlay, to the part
	 */
	private transient Map<String, BodyPart> byModelName;

	/**
	 * @param pitch degrees about the x axis of the body after its yaw, -90 lies face down
	 * @param lift  blocks up (negative: down) before the pitch, at the scale of the body
	 * @param shift blocks back before the pitch, to keep a body lying face down over the place it stands on
	 */
	public record Crawl(float pitch, float lift, float shift) {
	}

	public boolean hasLegs() {
		for (BodyPart part : parts) if (part.isLeg()) return true;
		return false;
	}

	public BodyPart part(int index) {
		return parts.get(index);
	}

	public BodyPart part(String name) {
		return byName.get(name);
	}

	/**
	 * The part a model part of this name draws, as itself or as an overlay of it.
	 */
	public BodyPart forModelPart(String name) {
		return byModelName.get(name);
	}

	public boolean isOverlay(String modelPartName) {
		BodyPart part = byModelName.get(modelPartName);
		return part != null && !part.getName().equals(modelPartName);
	}

	void init() {
		if (id == null || parts == null || parts.isEmpty() || parts.size() > PartLimits.MAX_PARTS) {
			throw new IllegalArgumentException("profile without id or with a wrong number of parts");
		}
		if (material == null) material = Material.MEAT;
		if (entities == null) entities = List.of();
		byName = new HashMap<>();
		byModelName = new HashMap<>();
		for (int i = 0; i < parts.size(); i++) {
			BodyPart part = parts.get(i);
			part.init(i, material);
			if (byName.put(part.getName(), part) != null) throw new IllegalArgumentException("two parts named " + part.getName());
			byModelName.put(part.getName(), part);
			for (String overlay : part.getOverlays()) byModelName.put(overlay, part);
		}
		for (BodyPart part : parts) {
			if (part.getParent() != null && !byName.containsKey(part.getParent())) {
				throw new IllegalArgumentException("parent " + part.getParent() + " of " + part.getName() + " is not in " + id);
			}
		}
	}
}
