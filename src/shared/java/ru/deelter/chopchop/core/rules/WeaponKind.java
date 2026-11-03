package ru.deelter.chopchop.core.rules;

/**
 * What a hit was made with, as far as wounds care. Both backends sort items and projectiles into these.
 */
public enum WeaponKind {
	SWORD,
	AXE,
	TRIDENT,
	ARROW,
	/**
	 * mace, bare hand, any other item
	 */
	BLUNT,
	/**
	 * no impact point: explosions, falls and so on
	 */
	OTHER;

	public boolean edged() {
		return this == SWORD || this == AXE;
	}
}
