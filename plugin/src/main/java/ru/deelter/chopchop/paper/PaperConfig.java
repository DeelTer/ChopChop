package ru.deelter.chopchop.paper;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.deelter.chopchop.core.rules.WeaponKind;
import ru.deelter.chopchop.core.rules.WoundRules;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * config.yml to {@link WoundRules}. A missing key keeps the default of the rules.
 */
final class PaperConfig {
	private PaperConfig() {
	}

	static WoundRules read(FileConfiguration config) {
		WoundRules rules = new WoundRules();
		rules.setEnabled(config.getBoolean("enabled", rules.isEnabled()));
		rules.setPlayers(config.getBoolean("players", rules.isPlayers()));
		rules.setMobs(config.getBoolean("mobs", rules.isMobs()));
		rules.setWoundChance(config.getDouble("wounds.chance", rules.getWoundChance()));
		rules.setWoundRadiusBase(config.getDouble("wounds.radius-base", rules.getWoundRadiusBase()));
		rules.setWoundRadiusPerDamage(config.getDouble("wounds.radius-per-damage", rules.getWoundRadiusPerDamage()));
		rules.setWoundRadiusMax(config.getDouble("wounds.radius-max", rules.getWoundRadiusMax()));
		rules.setMaxWoundDepth(config.getInt("wounds.max-depth", rules.getMaxWoundDepth()));
		rules.setMaxCarvedShare(config.getDouble("wounds.max-carved-share", rules.getMaxCarvedShare()));
		rules.setSevering(config.getBoolean("severing.enabled", rules.isSevering()));
		rules.setSeverChance(config.getDouble("severing.chance", rules.getSeverChance()));
		rules.setDurabilityMultiplier(config.getDouble("severing.durability-multiplier", rules.getDurabilityMultiplier()));
		rules.setMinSeverDamage(config.getDouble("severing.min-damage", rules.getMinSeverDamage()));
		rules.setHeads(config.getBoolean("severing.heads", rules.isHeads()));
		rules.setDecapitationKills(config.getBoolean("severing.decapitation-kills", rules.isDecapitationKills()));
		rules.setHeadlessSeconds(config.getDouble("severing.headless-seconds", rules.getHeadlessSeconds()));
		rules.setHeadlessPlayerSeconds(config.getDouble("severing.headless-player-seconds", rules.getHeadlessPlayerSeconds()));
		rules.setLegless(config.getString("severing.legless", rules.getLegless()));
		rules.setCrawlSpeed(config.getDouble("severing.crawl-speed", rules.getCrawlSpeed()));
		rules.setLeglessMobs(config.getString("severing.legless-mobs", rules.getLeglessMobs()));
		rules.setLeglessHeadHits(config.getInt("severing.legless-head-hits", rules.getLeglessHeadHits()));
		rules.setOtherSources(config.getString("other-sources", rules.getOtherSources()));
		rules.setSlashLengthBase(config.getDouble("wounds.slash-length-base", rules.getSlashLengthBase()));
		rules.setSlashLengthPerDamage(config.getDouble("wounds.slash-length-per-damage", rules.getSlashLengthPerDamage()));
		rules.setSlashLengthMax(config.getDouble("wounds.slash-length-max", rules.getSlashLengthMax()));
		rules.setSlashWidth(config.getDouble("wounds.slash-width", rules.getSlashWidth()));
		rules.setExplosions(config.getBoolean("explosions.enabled", rules.isExplosions()));
		rules.setExplosionWounds(config.getInt("explosions.wounds", rules.getExplosionWounds()));
		rules.setExplosionSeverDamage(config.getDouble("explosions.sever-damage", rules.getExplosionSeverDamage()));
		rules.setExplosionSeverChancePerDamage(config.getDouble("explosions.sever-chance-per-damage", rules.getExplosionSeverChancePerDamage()));
		rules.setExplosionDeathTakesHead(config.getBoolean("explosions.death-takes-head", rules.isExplosionDeathTakesHead()));
		rules.setKillCamThreats(config.getString("kill-cam.threats", rules.getKillCamThreats()));
		rules.setKillCamThreatRadius(config.getDouble("kill-cam.threat-radius", rules.getKillCamThreatRadius()));
		rules.setKillCamFreezeSeconds(config.getDouble("kill-cam.freeze-seconds", rules.getKillCamFreezeSeconds()));
		rules.setPersistPlayers(config.getBoolean("persist-players", rules.isPersistPlayers()));
		rules.setAxeExtraDepth(config.getInt("wounds.axe-extra-depth", rules.getAxeExtraDepth()));
		rules.setSeverChancePerDamage(config.getDouble("severing.chance-per-damage", rules.getSeverChancePerDamage()));
		rules.setSeverHeavyBonus(config.getDouble("severing.heavy-bonus", rules.getSeverHeavyBonus()));
		rules.setLethalSeverChance(config.getDouble("severing.lethal-chance", rules.getLethalSeverChance()));
		rules.setLethalHeadChance(config.getDouble("severing.lethal-head-chance", rules.getLethalHeadChance()));
		rules.setHeadHitDamage(config.getDouble("severing.head-hit-damage", rules.getHeadHitDamage()));
		rules.setExplosionWoundDepth(config.getInt("explosions.wound-depth", rules.getExplosionWoundDepth()));
		rules.setExplosionWoundScale(config.getDouble("explosions.wound-scale", rules.getExplosionWoundScale()));
		rules.setExplosionCarvedShare(config.getDouble("explosions.max-carved-share", rules.getExplosionCarvedShare()));
		rules.setDisarmMobs(config.getBoolean("disarm.mobs", rules.isDisarmMobs()));
		rules.setDisarmDrops(config.getBoolean("disarm.drops", rules.isDisarmDrops()));
		rules.setCorpseSeconds(config.getDouble("corpse-seconds", rules.getCorpseSeconds()));
		ConfigurationSection weapons = config.getConfigurationSection("weapons");
		if (weapons != null) {
			Map<WeaponKind, WoundRules.WeaponRule> map = new EnumMap<>(WeaponKind.class);
			for (WeaponKind kind : WeaponKind.values()) {
				WoundRules.WeaponRule base = rules.weapon(kind);
				ConfigurationSection section = weapons.getConfigurationSection(kind.name().toLowerCase(Locale.ROOT));
				map.put(kind, section == null ? base : new WoundRules.WeaponRule(
						section.getDouble("wound-chance", base.woundChance()),
						section.getDouble("wound-size", base.woundSize()),
						section.getDouble("sever", base.sever())));
			}
			rules.setWeapons(map);
		}
		return rules;
	}
}
