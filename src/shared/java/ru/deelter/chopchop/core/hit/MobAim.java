package ru.deelter.chopchop.core.hit;

import java.util.random.RandomGenerator;

/**
 * Where a mob's blow lands. A mob looks its target in the eyes, so a ray along its look always meets the head: fine
 * for a sword or an axe, that is how heads fly. A mob that bites and claws with bare hands tears at the whole body
 * instead: the legs a quarter of the time, the chest and arms most often, the head now and then.
 */
public final class MobAim {
	private MobAim() {
	}

	/**
	 * A point on the target to aim at, relative to its feet, in blocks: x and z across the body (wide enough to take
	 * an arm at the side), y up.
	 *
	 * @param width  of the target's box
	 * @param height of the target's box
	 */
	public static double[] spread(RandomGenerator random, double width, double height) {
		double r = random.nextDouble();
		double y;
		if (r < 0.25) y = 0.1 + random.nextDouble() * 0.35;
		else if (r < 0.85) y = 0.45 + random.nextDouble() * 0.33;
		else y = 0.8 + random.nextDouble() * 0.15;
		// the arms hang outside the box of a humanoid: wider than the box
		double across = width * 1.5;
		return new double[]{(random.nextDouble() - 0.5) * across, y * height, (random.nextDouble() - 0.5) * across};
	}
}
