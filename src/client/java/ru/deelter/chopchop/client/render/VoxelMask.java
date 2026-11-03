package ru.deelter.chopchop.client.render;

/**
 * Which voxels of a part grid are gone for one draw. Live parts, armor and fallen pieces each see a part differently.
 */
@FunctionalInterface
public interface VoxelMask {
	boolean gone(int x, int y, int z);
}
