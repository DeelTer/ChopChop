package ru.deelter.chopchop.client.api;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ru.deelter.chopchop.client.Corpses;
import ru.deelter.chopchop.client.ModelPick;

import java.util.ArrayList;
import java.util.List;

/**
 * The client side of ChopChop, for a mod that has blades of its own (a VR combat mod, say) and wants them to meet
 * and cut the dead bodies ChopChop keeps on the client. Client thread only. The cut itself is decided by the server,
 * as a click at the body is.
 */
public final class ChopChopClientApi {
	private ChopChopClientApi() {
	}

	/**
	 * A dead body on this client.
	 *
	 * @param id  the id the server knows it by
	 * @param box about where it lies, for something solid to meet it
	 */
	public record Corpse(int id, AABB box) {
	}

	/**
	 * the dead bodies whose boxes touch {@code area}
	 */
	public static List<Corpse> corpses(AABB area) {
		List<Corpse> found = new ArrayList<>();
		for (Corpses.Body body : Corpses.all()) {
			AABB box = Corpses.box(body.id());
			if (box != null && box.intersects(area)) found.add(new Corpse(body.id(), box));
		}
		return found;
	}

	/**
	 * a dead body is under the crosshair: a click now cuts it (another mod's click handling should leave it be)
	 */
	public static boolean corpseUnderCrosshair() {
		return ModelPick.corpseAimed();
	}

	/**
	 * Cuts the body where a blade from {@code from} along {@code dir} meets it, within {@code length}. Cuts closer than
	 * a sword swing to the last one are not sent.
	 *
	 * @return the blade met the body
	 */
	public static boolean cut(int id, Vec3 from, Vec3 dir, double length) {
		return ModelPick.cutCorpseAlong(id, from, dir, length);
	}

	/**
	 * the hand on that side ("left" or "right") of an entity this client draws is cut off: another mod may fight with
	 * the other one instead
	 */
	public static boolean isHandless(net.minecraft.world.entity.Entity entity, String side) {
		var state = ru.deelter.chopchop.client.ClientWounds.get(entity.getId());
		return state != null && state.handless(side);
	}
}
