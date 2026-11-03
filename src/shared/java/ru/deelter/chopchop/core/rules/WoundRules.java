package ru.deelter.chopchop.core.rules;

import lombok.Getter;
import lombok.Setter;

import java.util.EnumMap;
import java.util.Map;

/**
 * The tunable rules of wounds and severing, the same for the Fabric server and the Paper plugin. Each backend fills
 * it from its own config file; the defaults are the values here.
 */
@Getter
@Setter
public final class WoundRules {
	private boolean enabled = true;
	private boolean players = true;
	private boolean mobs = true;
	/**
	 * chance that a hit carves a wound at all
	 */
	private double woundChance = 0.75;
	/**
	 * radius of a wound in voxels: base, plus per point of damage, at most max
	 */
	private double woundRadiusBase = 1.0;
	private double woundRadiusPerDamage = 0.12;
	private double woundRadiusMax = 2.4;
	/**
	 * A sword or an axe cuts a scar across the body instead of a round hole: length in voxels, base plus per point
	 * of damage, at most max, and its width (a radius around the line of the cut)
	 */
	private double slashLengthBase = 2.5;
	private double slashLengthPerDamage = 0.35;
	private double slashLengthMax = 7.0;
	private double slashWidth = 0.85;
	/**
	 * how deep under the original surface a wound may go, in voxels. 0 is the surface only
	 */
	private int maxWoundDepth = 1;
	/**
	 * a part never loses more than this share of its voxels to wounds
	 */
	private double maxCarvedShare = 0.45;
	private boolean severing = true;
	/**
	 * chance a segment comes off once its durability is used up
	 */
	private double severChance = 0.4;
	/**
	 * multiplies the durability of every segment of every profile
	 */
	private double durabilityMultiplier = 1.0;
	/**
	 * a single hit weaker than this never severs
	 */
	private double minSeverDamage = 4.0;
	/**
	 * Heads come off. A head segment counts hits (three chops by default) instead of damage
	 */
	private boolean heads = true;
	/**
	 * A body without its head dies: a player after {@link #headlessPlayerSeconds}, a mob after running around blind for
	 * {@link #headlessSeconds}
	 */
	private boolean decapitationKills = true;
	private double headlessSeconds = 3.0;
	/**
	 * a player without a head stands this long before falling: long enough to watch it from the flying head
	 */
	private double headlessPlayerSeconds = 2.0;
	/**
	 * What happens when the last leg would come off: "crawl" (it comes off, the body drops and crawls),
	 * "protect" (the last leg never comes off), "none" (it comes off and nothing else happens)
	 */
	private String legless = "crawl";
	/**
	 * speed of a crawling body, a share of its normal speed
	 */
	private double crawlSpeed = 0.35;
	/**
	 * What a mob without legs does: "lie" (lies still with its AI off, only its head follows the nearest player) or
	 * "crawl" (keeps after its target at {@link #crawlSpeed}). Players always crawl in the vanilla pose
	 */
	private String leglessMobs = "lie";
	/**
	 * chops it takes to take the head of a body without legs, lying there: 1, the next blow. 0 keeps the usual count
	 */
	private int leglessHeadHits = 1;
	/**
	 * "skip" or "coarse": what explosions, falls, fire and the like do. Coarse picks a part that fits the source
	 */
	private String otherSources = "skip";
	/**
	 * Explosions tear bodies by how hard they hit: craters on the side facing the blast, and above
	 * {@link #explosionSeverDamage} limbs fly off, the closer the more
	 */
	private boolean explosions = true;
	/**
	 * how many spots of the body a blast tears at
	 */
	private int explosionWounds = 5;
	private double explosionSeverDamage = 8.0;
	/**
	 * chance of each limb facing the blast to come off, per point of damage above explosionSeverDamage
	 */
	private double explosionSeverChancePerDamage = 0.06;
	/**
	 * A player killed by a blast always loses the head, thrown away from the blast
	 */
	private boolean explosionDeathTakesHead = true;
	/**
	 * What happens to the kill cam when monsters near the player are after it: "skip" (no kill cam), "freeze" (they
	 * stand still while it plays), "ignore" (it plays anyway)
	 */
	private String killCamThreats = "skip";
	private double killCamThreatRadius = 8.0;
	private double killCamFreezeSeconds = 2.5;
	private Map<WeaponKind, WeaponRule> weapons = defaultWeapons();
	/**
	 * Player wounds survive a restart of the server. Off: they last as long as the server runs or until respawn
	 */
	private boolean persistPlayers = false;

	public boolean crawls() {
		return "crawl".equalsIgnoreCase(legless);
	}

	public boolean mobsLie() {
		return crawls() && "lie".equalsIgnoreCase(leglessMobs);
	}

	public boolean protectsLastLeg() {
		return "protect".equalsIgnoreCase(legless);
	}

	public WeaponRule weapon(WeaponKind kind) {
		WeaponRule rule = weapons == null ? null : weapons.get(kind);
		return rule == null ? WeaponRule.NONE : rule;
	}

	private static Map<WeaponKind, WeaponRule> defaultWeapons() {
		Map<WeaponKind, WeaponRule> map = new EnumMap<>(WeaponKind.class);
		map.put(WeaponKind.SWORD, new WeaponRule(1.0, 1.0, 1.0));
		map.put(WeaponKind.AXE, new WeaponRule(1.0, 1.25, 1.4));
		map.put(WeaponKind.TRIDENT, new WeaponRule(1.0, 1.1, 0.0));
		map.put(WeaponKind.ARROW, new WeaponRule(0.9, 0.8, 0.0));
		map.put(WeaponKind.BLUNT, new WeaponRule(0.45, 0.8, 0.0));
		map.put(WeaponKind.OTHER, new WeaponRule(0.5, 1.0, 0.0));
		return map;
	}

	/**
	 * @param woundChance multiplies {@link #woundChance}
	 * @param woundSize   multiplies the wound radius
	 * @param sever       multiplies damage towards severing, 0 never severs
	 */
	public record WeaponRule(double woundChance, double woundSize, double sever) {
		public static final WeaponRule NONE = new WeaponRule(0, 0, 0);
	}
}
