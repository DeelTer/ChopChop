package ru.deelter.chopchop.client.compat;

import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.client.ClientWounds;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.wound.PartState;
import ru.deelter.chopchop.core.wound.WoundState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Predicate;

/**
 * ModelInteractions grabs model parts by their boxes, and knows nothing of what ChopChop cut off. Its ray and touch
 * tests get one more filter here: a part that is gone (a head, an arm cut at the shoulder) can't be grabbed, a stump
 * can, and wounds follow whatever it bends anyway, since they are drawn in the space of each part.
 * <p>
 * Only reflection on two getters of ModelInteractions ({@code PlayerRig.getEntityId}, {@code Bone.getName}): no
 * compile time dependency. If they change, the filter lets everything through and says so once.
 */
public final class ModelInteractionsCompat {
	private static final ThreadLocal<Integer> ENTITY = ThreadLocal.withInitial(() -> -1);
	private static MethodHandle entityId, boneName;
	private static boolean broken;

	private ModelInteractionsCompat() {
	}

	/**
	 * Called at the start of a hit test with the rig it tests.
	 */
	public static void begin(Object rig) {
		ENTITY.set(-1);
		if (broken || rig == null) return;
		try {
			if (entityId == null) {
				entityId = MethodHandles.publicLookup().findVirtual(rig.getClass(), "getEntityId", MethodType.methodType(int.class));
			}
			ENTITY.set((int) entityId.invoke(rig));
		} catch (Throwable e) {
			fail(e);
		}
	}

	/**
	 * The filter of a hit test, and on top of it ours. The entity is read when a bone is tested, not now: the hook
	 * that notes the rig may run after this one.
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static Predicate wrap(Predicate filter) {
		if (broken || filter == null) return filter;
		return bone -> filter.test(bone) && reachable(bone);
	}

	private static boolean reachable(Object bone) {
		int entity = ENTITY.get();
		if (broken || entity < 0) return true;
		WoundState state = ClientWounds.get(entity);
		if (state == null) return true;
		try {
			if (boneName == null) boneName = MethodHandles.publicLookup().findVirtual(bone.getClass(), "getName", MethodType.methodType(String.class));
			String name = (String) boneName.invoke(bone);
			if (name == null) return true;
			BodyPart part = state.getProfile().forModelPart(name);
			if (part == null) return true;
			PartState partState = state.part(part.getIndex());
			// all of it is gone: nothing to take hold of
			return partState.getSeveredAt() != 0;
		} catch (Throwable e) {
			fail(e);
			return true;
		}
	}

	private static void fail(Throwable e) {
		if (broken) return;
		broken = true;
		ChopChop.LOG.warn("ModelInteractions changed, cut off parts can be grabbed again: {}", e.toString());
	}
}
