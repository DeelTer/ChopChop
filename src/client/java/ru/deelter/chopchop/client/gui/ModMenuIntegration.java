package ru.deelter.chopchop.client.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Adds the settings button to the mod list of Mod Menu. The screen is built with Cloth Config when it is there;
 * {@link ClothScreen} is not even looked at otherwise.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> FabricLoader.getInstance().isModLoaded("cloth-config") ? ClothScreen.create(parent) : new NoClothScreen(parent);
	}
}
