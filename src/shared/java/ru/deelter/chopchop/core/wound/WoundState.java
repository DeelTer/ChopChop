package ru.deelter.chopchop.core.wound;

import lombok.Getter;
import ru.deelter.chopchop.core.profile.BodyProfile;

/**
 * All the damage of one entity, part by part in the order of its profile.
 */
public final class WoundState {
	@Getter
	private final BodyProfile profile;
	private final PartState[] parts;
	/**
	 * counted up on every change, lets a client drop a state older than the one it has
	 */
	@Getter
	private int revision;

	public WoundState(BodyProfile profile) {
		this.profile = profile;
		this.parts = new PartState[profile.getParts().size()];
		for (int i = 0; i < parts.length; i++) parts[i] = new PartState(profile.part(i));
	}

	public PartState part(int index) {
		return parts[index];
	}

	public int partCount() {
		return parts.length;
	}

	public boolean pristine() {
		for (PartState part : parts) {
			if (!part.pristine()) return false;
		}
		return true;
	}

	/**
	 * Every leg of a body that stands on legs is cut off.
	 */
	public boolean legless() {
		boolean any = false;
		for (PartState part : parts) {
			if (!part.getPart().isLeg()) continue;
			any = true;
			if (!part.severed()) return false;
		}
		return any;
	}

	/**
	 * The head came off whole, at the neck.
	 */
	public boolean headless() {
		var head = profile.part("head");
		return head != null && parts[head.getIndex()].getSeveredAt() == 0;
	}

	public void touch() {
		revision++;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	public void clear() {
		for (PartState part : parts) part.clear();
		revision++;
	}
}
