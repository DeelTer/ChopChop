package ru.deelter.chopchop.client.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Without Cloth Config: where the settings file is.
 */
public class NoClothScreen extends Screen {
	private static final int WIDTH = 220;
	private final Screen parent;

	public NoClothScreen(Screen parent) {
		super(Component.translatable("chopchop.config.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		MultiLineTextWidget text = new MultiLineTextWidget(Component.translatable("chopchop.config.noCloth"), font);
		text.setMaxWidth(WIDTH);
		text.setX((width - text.getWidth()) / 2);
		text.setY(height / 2 - 30);
		addRenderableWidget(text);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds((width - WIDTH) / 2, height / 2 + 20, WIDTH, 20).build());
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
