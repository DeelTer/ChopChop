package ru.deelter.chopchop.core.profile;

/**
 * Bounds that keep a profile, and every message built on it, small.
 */
public final class PartLimits {
	public static final int MAX_VOXELS = 16 * 16 * 16;
	public static final int MAX_PARTS = 32;

	private PartLimits() {
	}
}
