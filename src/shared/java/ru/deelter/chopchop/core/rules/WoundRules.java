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
	 * extra depth of the wounds of an axe, voxels: it chops pieces out, a sword cuts
	 */
	private int axeExtraDepth = 1;
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
	 * Every point of damage above {@link #minSeverDamage} adds this to {@link #severChance}: a netherite axe takes a
	 * limb far more often than a stone sword. The chance never goes above 0.95
	 */
	private double severChancePerDamage = 0.05;
	/**
	 * Heavy blows wear a segment down faster: its damage towards severing is multiplied by
	 * {@code 1 + (damage - minSeverDamage) * severHeavyBonus}
	 */
	private double severHeavyBonus = 0.15;
	/**
	 * The blow that kills a body with a sword or an axe takes the limb it hits with this chance, whatever the
	 * durability: most weapons kill a zombie in three or four blows, long before a limb would wear through
	 */
	private double lethalSeverChance = 0.6;
	/**
	 * The same for the head: a killing chop at the head takes it with this chance
	 */
	private double lethalHeadChance = 0.45;
	/**
	 * A chop at the head counts as {@code 1 + damage / headHitDamage} chops: a heavy weapon takes the head in fewer
	 * blows. 0 counts every chop as one
	 */
	private double headHitDamage = 8.0;
	/**
	 * Sharpness on a sword or an axe: per level, limbs wear through this much faster and come off this much more
	 * often (0.15: Sharpness V is +75%). And every {@code sharpnessHeadLevels} levels add a chop to each chop at a head
	 * (3: Sharpness III and up take a head in two chops). 0 turns either off
	 */
	private double sharpnessSeverBonus = 0.15;
	private int sharpnessHeadLevels = 3;
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
	 * A body with a leg cut off is slowed by Slowness of this level (1: Slowness I), for as long as the leg is gone:
	 * the effect is put back if milk or a command takes it. 0 turns it off
	 */
	private int limpSlowness = 1;
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
	/**
	 * A blast tears chunks out of the body rather than nicks: their depth in voxels and how much bigger they are than
	 * a wound of a hit with the same damage
	 */
	private int explosionWoundDepth = 2;
	private double explosionWoundScale = 1.6;
	/**
	 * a body a blast tears at loses at most this share of a part, more than to blows
	 */
	private double explosionCarvedShare = 0.6;
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
	/**
	 * A mob that loses the arm with a weapon or a bow in its hand loses the item too, and one without any arm
	 * attacks no more and picks nothing up: a skeleton without its bow arm stops shooting. Players are not touched
	 */
	private boolean disarmMobs = true;
	/**
	 * the item of a cut off hand drops to the ground; off, it is gone (no farm of mob gear)
	 */
	private boolean disarmDrops = false;
	/**
	 * Dead mobs stay on the ground as bodies for this long, falling the way the last blow pushed them, then sink
	 * away. 0: they go the vanilla way
	 */
	private double corpseSeconds = 18.0;
	/**
	 * dead players lie as bodies too: the clients draw a copy with their skin, armour and wounds
	 */
	private boolean playerCorpses = true;
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
