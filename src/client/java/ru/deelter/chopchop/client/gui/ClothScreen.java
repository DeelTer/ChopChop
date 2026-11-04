package ru.deelter.chopchop.client.gui;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.deelter.chopchop.client.config.ClientConfig;

/**
 * The settings screen, on Cloth Config. Only loaded once Cloth Config is known to be installed.
 */
public final class ClothScreen {
	private ClothScreen() {
	}

	public static Screen create(Screen parent) {
		ClientConfig config = ClientConfig.get();
		ClientConfig defaults = new ClientConfig();
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("chopchop.config.title"))
				.setSavingRunnable(ClientConfig::save);
		ConfigEntryBuilder entries = builder.entryBuilder();
		ConfigCategory visual = builder.getOrCreateCategory(Component.translatable("chopchop.config.visual"));
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.enabled"), config.isEnabled())
				.setDefaultValue(defaults.isEnabled()).setSaveConsumer(config::setEnabled).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.particles"), config.isParticles())
				.setDefaultValue(defaults.isParticles()).setSaveConsumer(config::setParticles).build());
		visual.addEntry(entries.startIntSlider(Component.translatable("chopchop.config.particleAmount"),
						(int) Math.round(config.getParticleAmount() * 100), 0, 400)
				.setDefaultValue(100).setSaveConsumer(value -> config.setParticleAmount(value / 100.0)).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.sounds"), config.isSounds())
				.setDefaultValue(defaults.isSounds()).setSaveConsumer(config::setSounds).build());
		visual.addEntry(entries.startIntSlider(Component.translatable("chopchop.config.soundVolume"),
						(int) Math.round(config.getSoundVolume() * 100), 0, 200)
				.setDefaultValue(100).setSaveConsumer(value -> config.setSoundVolume(value / 100.0)).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.debris"), config.isDebris())
				.setDefaultValue(defaults.isDebris()).setSaveConsumer(config::setDebris).build());
		visual.addEntry(entries.startIntSlider(Component.translatable("chopchop.config.debrisSeconds"), config.getDebrisSeconds(), 1, 60)
				.setDefaultValue(defaults.getDebrisSeconds()).setSaveConsumer(config::setDebrisSeconds).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.debrisKick"), config.isDebrisKick())
				.setDefaultValue(defaults.isDebrisKick()).setSaveConsumer(config::setDebrisKick).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.corpses"), config.isCorpses())
				.setDefaultValue(defaults.isCorpses()).setSaveConsumer(config::setCorpses).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.bones"), config.isBones())
				.setDefaultValue(defaults.isBones()).setSaveConsumer(config::setBones).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.skeletonInside"), config.isSkeletonInside())
				.setDefaultValue(defaults.isSkeletonInside()).setSaveConsumer(config::setSkeletonInside).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.firstPerson"), config.isFirstPerson())
				.setDefaultValue(defaults.isFirstPerson()).setSaveConsumer(config::setFirstPerson).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.hideItems"), config.isHideItems())
				.setDefaultValue(defaults.isHideItems()).setSaveConsumer(config::setHideItems).build());
		visual.addEntry(entries.startIntSlider(Component.translatable("chopchop.config.maxDistance"), config.getMaxDistance(), 8, 256)
				.setDefaultValue(defaults.getMaxDistance()).setSaveConsumer(config::setMaxDistance).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.headCam"), config.isHeadCam())
				.setDefaultValue(defaults.isHeadCam()).setSaveConsumer(config::setHeadCam).build());
		visual.addEntry(entries.startIntSlider(Component.translatable("chopchop.config.headCamSeconds"), (int) Math.round(config.getHeadCamSeconds()), 0, 15)
				.setDefaultValue((int) defaults.getHeadCamSeconds()).setSaveConsumer(value -> config.setHeadCamSeconds(value)).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.fountains"), config.isFountains())
				.setDefaultValue(defaults.isFountains()).setSaveConsumer(config::setFountains).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.limbRules"), config.isLimbRules())
				.setDefaultValue(defaults.isLimbRules()).setSaveConsumer(config::setLimbRules).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.ownCorpse"), config.isOwnCorpse())
				.setDefaultValue(defaults.isOwnCorpse()).setSaveConsumer(config::setOwnCorpse).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.woundsThroughArmor"), config.isWoundsThroughArmor())
				.setDefaultValue(defaults.isWoundsThroughArmor()).setSaveConsumer(config::setWoundsThroughArmor).build());
		visual.addEntry(entries.startBooleanToggle(Component.translatable("chopchop.config.killCam"), config.isKillCam())
				.setDefaultValue(defaults.isKillCam()).setSaveConsumer(config::setKillCam).build());
		visual.addEntry(entries.startIntSlider(Component.translatable("chopchop.config.killCamSeconds"), (int) Math.round(config.getKillCamSeconds() * 10), 5, 60)
				.setDefaultValue((int) Math.round(defaults.getKillCamSeconds() * 10)).setSaveConsumer(value -> config.setKillCamSeconds(value / 10.0)).build());
		return builder.build();
	}
}
