package ru.deelter.chopchop.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import ru.deelter.chopchop.client.ClientWounds;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The hands of the local player in first person. The game draws an arm alone, as a part without an entity or a
 * render state, so the arm part itself is what ties that draw to the player's wounds.
 */
public final class FirstPerson {
	private static final Map<ModelPart, WoundState> ARMS = new IdentityHashMap<>();

	private FirstPerson() {
	}

	public static void noteArm(ModelPart arm) {
		WoundState wounds = ownWounds();
		if (wounds == null || !ClientConfig.get().isFirstPerson()) ARMS.remove(arm);
		else ARMS.put(arm, wounds);
	}

	public static WoundState forArm(ModelPart root) {
		return ARMS.get(root);
	}

	public static WoundState ownWounds() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || !ClientConfig.get().isEnabled()) return null;
		return ClientWounds.get(minecraft.player.getId());
	}

	/**
	 * Whether the hand on this side is cut off. A cut anywhere on an arm takes the hand with it.
	 */
	public static boolean handGone(WoundState wounds, HumanoidArm side) {
		if (wounds == null) return false;
		String name = side == HumanoidArm.RIGHT ? "right" : "left";
		for (int i = 0; i < wounds.partCount(); i++) {
			BodyPart part = wounds.getProfile().part(i);
			if (name.equals(part.getHand()) && wounds.part(i).severed()) return true;
		}
		return false;
	}

	public static HumanoidArm side(Player player, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
	}
}
