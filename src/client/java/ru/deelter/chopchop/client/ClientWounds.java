package ru.deelter.chopchop.client;

import net.minecraft.client.Minecraft;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

import java.util.*;

/**
 * What the server said about the wounds of each entity, by network id. A state is never changed once stored, a
 * new message replaces it whole, so a render state can keep a reference to it for a frame.
 * <p>
 * Client thread only.
 */
public final class ClientWounds {
	/**
	 * a state for an entity the client never saw is dropped after this long
	 */
	private static final long ORPHAN_NANOS = 10_000_000_000L;
	private static final long DETACH_NANOS = 1_000_000_000L;

	private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

	private ClientWounds() {
	}

	/**
	 * A limb that came off and was not drawn yet: the next draw of its entity turns it into a falling piece.
	 *
	 * @param before the part as it was before the cut
	 * @param flung  torn off by a blast: it flies away hard
	 * @param away   where the blast met it, in the world: it flies away from there. Null: away from the body
	 */
	public record Detach(int part, int cut, PartState before, long nanos, boolean flung, net.minecraft.world.phys.Vec3 away) {
	}

	public static final class Entry {
		private WoundState state;
		private final List<Detach> detaches = new ArrayList<>(1);
		private long receivedNanos = System.nanoTime();
		private boolean seen;

		public WoundState state() {
			return state;
		}
	}

	public static WoundState get(int entityId) {
		Entry entry = ENTRIES.get(entityId);
		if (entry == null) return null;
		entry.seen = true;
		return entry.state;
	}

	public static void put(int entityId, WoundState state) {
		// messages of one connection arrive in the order they were sent: the last one is the truth
		Entry entry = ENTRIES.computeIfAbsent(entityId, id -> new Entry());
		entry.state = state;
		entry.receivedNanos = System.nanoTime();
	}

	public static void clear(int entityId) {
		ENTRIES.remove(entityId);
	}

	public static void clearAll() {
		ENTRIES.clear();
	}

	/**
	 * Notes a cut the state of which is about to arrive: the shape before it is what falls off.
	 */
	public static void noteSever(int entityId, int part, int cut, boolean flung, net.minecraft.world.phys.Vec3 away) {
		Entry entry = ENTRIES.computeIfAbsent(entityId, id -> new Entry());
		// nothing known before the cut: the part was whole, and its profile comes with the state that follows
		PartState before = entry.state != null && part < entry.state.partCount() ? entry.state.part(part).copy() : null;
		entry.detaches.add(new Detach(part, cut, before, System.nanoTime(), flung, away));
	}

	public static List<Detach> takeDetaches(int entityId) {
		Entry entry = ENTRIES.get(entityId);
		if (entry == null || entry.detaches.isEmpty()) return List.of();
		List<Detach> list = List.copyOf(entry.detaches);
		entry.detaches.clear();
		return list;
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft.level == null) {
			ENTRIES.clear();
			return;
		}
		long now = System.nanoTime();
		Iterator<Map.Entry<Integer, Entry>> it = ENTRIES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Entry> e = it.next();
			Entry entry = e.getValue();
			entry.detaches.removeIf(d -> now - d.nanos() > DETACH_NANOS);
			if (entry.state == null && entry.detaches.isEmpty()) {
				it.remove();
				continue;
			}
			// states sent ahead of the entity itself wait for it a while
			if (!entry.seen && minecraft.level.getEntity(e.getKey()) == null && now - entry.receivedNanos > ORPHAN_NANOS) it.remove();
		}
	}

	public static int size() {
		return ENTRIES.size();
	}
}
