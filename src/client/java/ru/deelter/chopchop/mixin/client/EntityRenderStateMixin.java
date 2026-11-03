package ru.deelter.chopchop.mixin.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ru.deelter.chopchop.client.render.ChopRenderState;
import ru.deelter.chopchop.core.wound.WoundState;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements ChopRenderState {
	@Unique
	private WoundState chopchop$wounds;
	@Unique
	private int chopchop$entityId = -1;
	@Unique
	private Model<?> chopchop$main;
	@Unique
	private final double[] chopchop$camera = new double[3];

	@Override
	public WoundState chopchop$wounds() {
		return chopchop$wounds;
	}

	@Override
	public int chopchop$entityId() {
		return chopchop$entityId;
	}

	@Override
	public void chopchop$set(int entityId, WoundState wounds) {
		chopchop$entityId = entityId;
		chopchop$wounds = wounds;
	}

	@Override
	public Model<?> chopchop$mainModel() {
		return chopchop$main;
	}

	@Override
	public double[] chopchop$camera() {
		return chopchop$camera;
	}

	@Override
	public void chopchop$setSubmit(Model<?> main, double camX, double camY, double camZ) {
		chopchop$main = main;
		chopchop$camera[0] = camX;
		chopchop$camera[1] = camY;
		chopchop$camera[2] = camZ;
	}
}
