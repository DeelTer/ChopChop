package ru.deelter.chopchop;

import net.minecraft.world.entity.player.Player;

import java.util.function.Predicate;

/**
 * Which players have no legs and crawl. A player crawls the vanilla way, in the pose the game uses for tight gaps:
 * low box, low eyes, the crawl animation every client already draws. The pose is worked out on both sides every
 * tick, so the server and the local client each ask their own side here.
 */
public final class Crawling {
	public static Predicate<Player> server = player -> false;
	public static Predicate<Player> client = player -> false;

	private Crawling() {
	}

	public static boolean crawls(Player player) {
		return player.level().isClientSide() ? client.test(player) : server.test(player);
	}
}
