package ru.deelter.chopchop.core.profile;

/**
 * What is under the skin of a part.
 */
public final class Material {
	/**
	 * meat inside, a bone in a stump, red particles
	 */
	public static final String MEAT = "meat";
	/**
	 * bone all the way through, no blood: skeletons
	 */
	public static final String BONE = "bone";
	/**
	 * green goo inside, no bones, green particles: slimes and spiders
	 */
	public static final String SLIME = "slime";

	private Material() {
	}

	public static boolean bleeds(String material) {
		return !BONE.equals(material);
	}

	public static boolean goo(String material) {
		return SLIME.equals(material);
	}
}
