package ru.deelter.chopchop.core.server;

import ru.deelter.chopchop.core.hit.Hit;
import ru.deelter.chopchop.core.net.Protocol;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.Profiles;
import ru.deelter.chopchop.core.rules.WeaponKind;
import ru.deelter.chopchop.core.rules.WoundRules;
import ru.deelter.chopchop.core.wound.WoundEngine;
import ru.deelter.chopchop.core.wound.WoundState;

import java.io.IOException;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * The server's record of who is hurt where, keyed by entity UUID. Both backends keep one and only translate game
 * events into calls on it, so wounds, severing and messages follow the same rules on Fabric and on Paper.
 * <p>
 * Not thread safe: the server thread only.
 */
public final class WoundBook {
	private final Map<UUID, WoundState> states = new HashMap<>();
	private final Profiles profiles;
	private final RandomGenerator random;

	public WoundBook(Profiles profiles, RandomGenerator random) {
		this.profiles = profiles;
		this.random = random;
	}

	public WoundState get(UUID id) {
		return states.get(id);
	}

	/**
	 * The state to cast a hit against: the stored one, or a fresh, whole body that is stored only once it changes.
	 */
	public WoundState getOrBlank(UUID id, BodyProfile profile) {
		WoundState state = states.get(id);
		if (state != null && state.getProfile() == profile) return state;
		return new WoundState(profile);
	}

	/**
	 * Applies an accepted hit.
	 *
	 * @param entityId network id of the entity, for the messages
	 * @param attacker network id of who hit it, -1 for none
	 * @return messages for every client that sees the entity, in order; empty if nothing changed
	 */
	public List<byte[]> hit(UUID id, int entityId, WoundState state, Hit hit, WeaponKind weapon, float damage, WoundRules rules,
							 int attacker) {
		return hit(id, entityId, state, hit, weapon, damage, false, rules, attacker);
	}

	/**
	 * @param lethal the blow killed the body: it likely takes what it hits
	 */
	public List<byte[]> hit(UUID id, int entityId, WoundState state, Hit hit, WeaponKind weapon, float damage, boolean lethal,
							 WoundRules rules, int attacker) {
		WoundEngine.Outcome outcome = WoundEngine.apply(state, hit, weapon, damage, lethal, rules, random);
		List<byte[]> messages = new ArrayList<>(2);
		if (!outcome.changed()) {
			messages.add(Protocol.event(entityId, Protocol.EVENT_SPLASH, hit.part(), hit.model(), -1, attacker));
			return messages;
		}
		states.put(id, state);
		byte kind = outcome.severedAt() >= 0 ? Protocol.EVENT_SEVER : Protocol.EVENT_WOUND;
		// the event goes first: a client keeps the limb as it was before the cut, for the piece that falls off
		messages.add(Protocol.event(entityId, kind, hit.part(), hit.model(), outcome.severedAt(), attacker));
		messages.add(Protocol.state(entityId, state));
		return messages;
	}

	/**
	 * Applies an accepted blast: every limb that came off gets an event of its own, so each flies away on the
	 * clients, then one state for all of it.
	 */
	public List<byte[]> blast(UUID id, int entityId, WoundState state, List<Hit> hits, float damage, WoundRules rules) {
		return blast(id, entityId, state, hits, damage, null, rules);
	}

	/**
	 * @param headFacing when the blast killed a player: the point of the head facing the blast, the head comes off and
	 *                   flies away from there. Null otherwise
	 */
	public List<byte[]> blast(UUID id, int entityId, WoundState state, List<Hit> hits, float damage, float[] headFacing, WoundRules rules) {
		List<byte[]> messages = new ArrayList<>();
		if (hits.isEmpty() && headFacing == null) return messages;
		WoundEngine.BlastOutcome outcome = WoundEngine.blast(state, hits, damage, headFacing != null, rules, random);
		Hit first = hits.isEmpty() ? null : hits.getFirst();
		if (!outcome.changed()) {
			if (first == null) return messages;
			messages.add(Protocol.event(entityId, Protocol.EVENT_SPLASH, first.part(), first.model(), -1, -1));
			return messages;
		}
		states.put(id, state);
		for (int[] cut : outcome.severed()) {
			float[] at = first != null ? first.model() : headFacing;
			for (Hit hit : hits) if (hit.part() == cut[0]) at = hit.model();
			if (headFacing != null && cut[1] == 0 && "head".equals(state.getProfile().part(cut[0]).getName())) at = headFacing;
			messages.add(Protocol.event(entityId, Protocol.EVENT_BLAST, cut[0], at, cut[1], -1));
		}
		if (outcome.carved() > 0) messages.add(Protocol.event(entityId, Protocol.EVENT_WOUND, first.part(), first.model(), -1, -1));
		messages.add(Protocol.state(entityId, state));
		return messages;
	}

	/**
	 * A blow at a dead body, kept apart from the living ones (a dead player respawns under the same id). Every blow
	 * counts as a killing one: what is dead comes apart easily.
	 *
	 * @param corpseId the id the body goes by on the clients
	 */
	public List<byte[]> hitCorpse(int corpseId, WoundState state, Hit hit, WeaponKind weapon, float damage, WoundRules rules) {
		WoundEngine.Outcome outcome = WoundEngine.apply(state, hit, weapon, damage, true, rules, random);
		List<byte[]> messages = new ArrayList<>(2);
		if (!outcome.changed()) {
			messages.add(Protocol.event(corpseId, Protocol.EVENT_SPLASH, hit.part(), hit.model(), -1, -1));
			return messages;
		}
		byte kind = outcome.severedAt() >= 0 ? Protocol.EVENT_SEVER : Protocol.EVENT_WOUND;
		// no attacker: a head off a body that is dead already is no kill, no kill cam
		messages.add(Protocol.event(corpseId, kind, hit.part(), hit.model(), outcome.severedAt(), -1));
		messages.add(Protocol.state(corpseId, state));
		return messages;
	}

	/**
	 * A copy of a body, for a dead one that must not change with the living entity of the same id.
	 */
	public WoundState copyOf(WoundState state, BodyProfile profile) {
		if (state == null || state.pristine()) return new WoundState(profile);
		WoundState copy = decode(encode(state));
		return copy != null ? copy : new WoundState(profile);
	}

	/**
	 * Cuts a part off at a row, whatever the rules say: for the API.
	 *
	 * @param at       point of the cut in model space, pixels, for the effects; null: the middle of the cut
	 * @param flung    it flies off hard, as torn off by a blast
	 * @param attacker network id of who did it, -1 for none
	 * @return the messages for the clients, empty if nothing more came off
	 */
	public List<byte[]> sever(UUID id, int entityId, WoundState state, int part, int row, float[] at, boolean flung, int attacker) {
		List<byte[]> messages = new ArrayList<>(2);
		if (part < 0 || part >= state.partCount() || !state.part(part).sever(row)) return messages;
		state.touch();
		states.put(id, state);
		float[] point = at != null ? at : cutCenter(state, part, row);
		messages.add(Protocol.event(entityId, flung ? Protocol.EVENT_BLAST : Protocol.EVENT_SEVER, part, point, row, attacker));
		messages.add(Protocol.state(entityId, state));
		return messages;
	}

	/**
	 * Carves a wound at a hit, whatever the chance in the rules: for the API.
	 *
	 * @param slash      direction of a cut across the surface in voxels of the grid, null for a round hole
	 * @param halfLength half the length of the cut, voxels
	 * @param radius     radius of the hole or width of the cut, voxels
	 * @param depth      how deep under the surface it may go, voxels
	 * @return the messages for the clients, empty if nothing was carved
	 */
	public List<byte[]> wound(UUID id, int entityId, WoundState state, Hit hit, float[] slash, double halfLength, double radius, int depth,
							  int attacker) {
		List<byte[]> messages = new ArrayList<>(2);
		if (hit.part() < 0 || hit.part() >= state.partCount() || !state.part(hit.part()).getPart().isWoundable()) return messages;
		int carved = WoundEngine.carveAt(state.part(hit.part()), hit, slash, halfLength, radius, depth, 1.0);
		if (carved == 0) return messages;
		state.touch();
		states.put(id, state);
		messages.add(Protocol.event(entityId, Protocol.EVENT_WOUND, hit.part(), hit.model(), -1, attacker));
		messages.add(Protocol.state(entityId, state));
		return messages;
	}

	private static float[] cutCenter(WoundState state, int part, int row) {
		var def = state.getProfile().part(part);
		var center = ru.deelter.chopchop.core.hit.HitLocator.voxelCenter(state.getProfile(), def, def.gx() / 2, Math.min(def.gy() - 1, row), def.gz() / 2);
		return new float[]{(float) center.x, (float) center.y, (float) center.z};
	}

	/**
	 * @return whether there was anything to clear
	 */
	public boolean clear(UUID id) {
		return states.remove(id) != null;
	}

	public void forget(UUID id) {
		states.remove(id);
	}

	public Set<UUID> ids() {
		return Collections.unmodifiableSet(states.keySet());
	}

	public int size() {
		return states.size();
	}

	/**
	 * For keeping player wounds over a restart.
	 */
	public static String encode(WoundState state) {
		return Base64.getEncoder().encodeToString(Protocol.state(0, state));
	}

	public WoundState decode(String data) {
		try {
			Protocol.Message message = Protocol.readClientbound(Base64.getDecoder().decode(data), profiles);
			return message instanceof Protocol.Message.State s ? s.state() : null;
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}

	public void put(UUID id, WoundState state) {
		if (state != null && !state.pristine()) states.put(id, state);
	}
}
