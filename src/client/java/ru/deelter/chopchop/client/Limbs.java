package ru.deelter.chopchop.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.client.render.FirstPerson;
import ru.deelter.chopchop.client.render.Kicks;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.wound.WoundState;
import ru.deelter.chopchop.net.ChopPayload;

/**
 * What the local player can still do with the limbs it has, on its own client:
 * <ul>
 *     <li>a hand that is gone uses nothing: no tools, food, weapons or blocks from it, the other hand is tried;</li>
 *     <li>digging needs a limb: the main hand, else the other hand, else a leg kicks at the block (the others see
 *     that leg kick); with all four gone nothing is dug;</li>
 *     <li>a leg that is gone: no sprinting (the server slows the walk too, and lays a body with no legs down).</li>
 * </ul>
 * Only on a server that speaks ChopChop, where the wounds come from.
 */
public final class Limbs {
	/**
	 * how often a leg kicks while it digs, and is told to the server
	 */
	private static final long KICK_NANOS = 280_000_000L;
	private static long lastKick;

	private Limbs() {
	}

	private static WoundState own() {
		if (!ClientConfig.get().isLimbRules() || !ChopChopClient.serverSpeaks()) return null;
		return FirstPerson.ownWounds();
	}

	public static boolean isLocal(Entity entity) {
		return entity != null && entity == Minecraft.getInstance().player;
	}

	public static boolean handGone(InteractionHand hand) {
		LocalPlayer player = Minecraft.getInstance().player;
		WoundState wounds = own();
		return player != null && wounds != null && FirstPerson.handGone(wounds, FirstPerson.side(player, hand));
	}

	/**
	 * The off hand digs for a main hand that is gone.
	 */
	public static boolean offHandDigs() {
		return handGone(InteractionHand.MAIN_HAND) && !handGone(InteractionHand.OFF_HAND);
	}

	/**
	 * @return the leg that digs when both hands are gone (0 right, 1 left), -1 when a hand digs or no leg is left
	 */
	public static int diggingLeg() {
		WoundState wounds = own();
		if (wounds == null || !handGone(InteractionHand.MAIN_HAND) || !handGone(InteractionHand.OFF_HAND)) return -1;
		for (int leg = 0; leg < 2; leg++) {
			if (!legGone(wounds, leg == 0 ? HumanoidArm.RIGHT : HumanoidArm.LEFT)) return leg;
		}
		return -1;
	}

	/**
	 * Some limb is left to dig with.
	 */
	public static boolean canDig() {
		WoundState wounds = own();
		if (wounds == null || !handGone(InteractionHand.MAIN_HAND) || !handGone(InteractionHand.OFF_HAND)) return true;
		return diggingLeg() >= 0;
	}

	public static boolean limping() {
		WoundState wounds = own();
		return wounds != null && wounds.legsGone() > 0;
	}

	/**
	 * A swing of the arm while digging, made a kick of a leg, now and then, seen by the others too.
	 */
	public static void kick() {
		LocalPlayer player = Minecraft.getInstance().player;
		int leg = diggingLeg();
		long now = System.nanoTime();
		if (player == null || leg < 0 || now - lastKick < KICK_NANOS) return;
		lastKick = now;
		Kicks.start(player.getId(), leg);
		if (ClientPlayNetworking.canSend(ChopPayload.TYPE)) ClientPlayNetworking.send(new ChopPayload(Protocol.limb(leg)));
	}

	private static boolean legGone(WoundState wounds, HumanoidArm side) {
		String name = side == HumanoidArm.RIGHT ? "right_leg" : "left_leg";
		for (int i = 0; i < wounds.partCount(); i++) {
			BodyPart part = wounds.getProfile().part(i);
			if (part.isLeg() && part.getName().equals(name)) return wounds.part(i).severed();
		}
		// no such leg in this body: there is none to dig with
		return true;
	}

	/**
	 * A leg that is gone does not sprint: stop it if it does.
	 */
	public static void tick(Minecraft minecraft) {
		if (minecraft.player != null && minecraft.player.isSprinting() && limping()) minecraft.player.setSprinting(false);
	}
}
