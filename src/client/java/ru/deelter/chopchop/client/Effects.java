package ru.deelter.chopchop.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.client.config.ClientConfig;
import ru.deelter.chopchop.core.hit.BodyPose;
import ru.deelter.chopchop.core.hit.HitLocator;
import ru.deelter.chopchop.core.net.Protocol;
import net.minecraft.core.registries.BuiltInRegistries;
import ru.deelter.chopchop.core.profile.BodyPart;
import ru.deelter.chopchop.core.profile.BodyProfile;
import ru.deelter.chopchop.core.profile.Material;
import ru.deelter.chopchop.core.wound.WoundState;

/**
 * Blood and the little sounds of a hit. Played by each client from the server's event, the server sends no sound of
 * its own: the sounds are not registered, so vanilla clients and a Paper server never need to know them.
 */
public final class Effects {
	private static final SoundEvent CHOP = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(ChopChop.ID, "body.chop"));
	private static final SoundEvent SEVER = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(ChopChop.ID, "body.sever"));
	private static final DustParticleOptions BLOOD = new DustParticleOptions(0x9E1A1A, 1.1F);
	private static final DustParticleOptions BLOOD_DARK = new DustParticleOptions(0x6B0F12, 0.8F);
	private static final DustParticleOptions BONE_DUST = new DustParticleOptions(0xE9E2CC, 0.9F);

	private Effects() {
	}

	public static void play(Protocol.Message.Event event) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) return;
		Entity entity = ClientEntities.find(event.entityId());
		if (!(entity instanceof LivingEntity living)) return;
		ClientConfig config = ClientConfig.get();

		// the first event of an entity comes before its state: then the built-in profile of its type says what it is
		WoundState state = ClientWounds.get(event.entityId());
		Vector3d at = HitLocator.toWorld(pose(living, state), event.model()[0], event.model()[1], event.model()[2]);
		// a body on the ground does not stand as the rest pose: the blood comes from about its middle
		Vec3 lying = Corpses.middle(event.entityId());
		if (lying != null) at = new Vector3d(lying.x, lying.y, lying.z);
		BodyProfile profile = state != null ? state.getProfile()
				: ChopChop.getProfiles().forEntity(BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString());
		String material = Material.MEAT;
		if (profile != null && event.part() < profile.getParts().size()) {
			BodyPart part = profile.part(event.part());
			material = part.getMaterial();
		}
		boolean bleeds = Material.bleeds(material);

		if (config.isParticles() && config.getParticleAmount() > 0) {
			int base = switch (event.kind()) {
				case Protocol.EVENT_SEVER -> 22;
				case Protocol.EVENT_BLAST -> 34;
				case Protocol.EVENT_WOUND -> 9;
				default -> 3;
			};
			int count = (int) Math.round(base * config.getParticleAmount());
			RandomSource random = level.getRandom();
			for (int i = 0; i < count; i++) {
				DustParticleOptions dust = bleeds ? (random.nextInt(3) == 0 ? BLOOD_DARK : BLOOD) : BONE_DUST;
				double speed = event.kind() == Protocol.EVENT_BLAST ? 0.4 : event.kind() == Protocol.EVENT_SEVER ? 0.25 : 0.12;
				level.addParticle(dust, at.x, at.y, at.z,
						random.nextGaussian() * speed, random.nextDouble() * speed + 0.05, random.nextGaussian() * speed);
			}
		}
		if ((event.kind() == Protocol.EVENT_SEVER || event.kind() == Protocol.EVENT_BLAST) && event.severedAt() >= 0 && config.isFountains() && config.isParticles()) {
			FOUNTAINS.add(new Fountain(event.entityId(), event.part(), event.severedAt(), new int[]{event.severedAt() == 0 ? 50 : 30}));
		}
		if (config.isSounds() && config.getSoundVolume() > 0) {
			float volume = (float) config.getSoundVolume();
			float pitch = 0.9F + level.getRandom().nextFloat() * 0.25F;
			switch (event.kind()) {
				case Protocol.EVENT_SEVER -> level.playLocalSound(at.x, at.y, at.z, SEVER, living.getSoundSource(), volume, pitch, false);
				case Protocol.EVENT_BLAST -> level.playLocalSound(at.x, at.y, at.z, SEVER, living.getSoundSource(), volume, pitch - 0.15F, false);
				case Protocol.EVENT_WOUND -> level.playLocalSound(at.x, at.y, at.z, CHOP, living.getSoundSource(), volume * 0.8F, pitch, false);
				default -> level.playLocalSound(at.x, at.y, at.z, CHOP, living.getSoundSource(), volume * 0.35F, pitch + 0.15F, false);
			}
		}
	}

	/**
	 * The world point of an event, on the body as it is now; null when the entity is not here.
	 */
	static Vec3 worldPoint(Protocol.Message.Event event) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !(ClientEntities.find(event.entityId()) instanceof LivingEntity living)) return null;
		Vec3 lying = Corpses.middle(event.entityId());
		if (lying != null) return lying;
		Vector3d at = HitLocator.toWorld(pose(living, ClientWounds.get(event.entityId())), event.model()[0], event.model()[1], event.model()[2]);
		return new Vec3(at.x, at.y, at.z);
	}

	/**
	 * The body as the server finds hits on it: lying down when it crawls.
	 */
	static BodyPose pose(LivingEntity living, WoundState state) {
		BodyPose pose = new BodyPose(living.getX(), living.getY(), living.getZ(), living.yBodyRot, living.getScale(), 0);
		return ChopChopClient.serverSpeaks() ? pose.crawling(state, ChopChopClient.serverRules()) : pose;
	}

	/**
	 * A head hitting the ground: a squish that gets softer and higher with every hop.
	 */
	public static void bounce(Vec3 at, int bounce) {
		ClientLevel level = Minecraft.getInstance().level;
		ClientConfig config = ClientConfig.get();
		if (level == null) return;
		if (config.isSounds() && config.getSoundVolume() > 0) {
			level.playLocalSound(at.x, at.y, at.z, CHOP, SoundSource.NEUTRAL, (float) config.getSoundVolume() * 0.7F / (bounce + 1),
					1.15F + bounce * 0.12F, false);
		}
		if (config.isParticles()) {
			for (int i = 0; i < 4; i++) {
				level.addParticle(BLOOD, at.x, at.y - 0.1, at.z, level.getRandom().nextGaussian() * 0.08, 0.08, level.getRandom().nextGaussian() * 0.08);
			}
		}
	}

	/**
	 * A fresh stump squirting for a moment, from where the cut is on the body now.
	 */
	private record Fountain(int entityId, int part, int cut, int[] ticks) {
	}

	private static final List<Fountain> FOUNTAINS = new ArrayList<>();

	public static void clear() {
		FOUNTAINS.clear();
	}

	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null) {
			FOUNTAINS.clear();
			return;
		}
		if (minecraft.isPaused()) return;
		Iterator<Fountain> it = FOUNTAINS.iterator();
		while (it.hasNext()) {
			Fountain f = it.next();
			WoundState state = ClientWounds.get(f.entityId());
			if (--f.ticks()[0] < 0 || state == null || f.part() >= state.partCount()
					|| !(level.getEntity(f.entityId()) instanceof LivingEntity living)) {
				it.remove();
				continue;
			}
			BodyPart part = state.getProfile().part(f.part());
			if (!Material.bleeds(part.getMaterial())) {
				it.remove();
				continue;
			}
			float[] b = part.getBox();
			// the cut plane; a whole part off (a head) leaves its stump where the part was fixed, its bottom
			float y = f.cut() == 0 ? b[4] : b[1] + (b[4] - b[1]) * f.cut() / part.gy();
			Vector3d model = HitLocator.partToModel(state.getProfile(), part)
					.transformPosition(new Vector3d((b[0] + b[3]) / 2, y, (b[2] + b[5]) / 2));
			BodyPose pose = pose(living, state);
			Vector3d at = HitLocator.toWorld(pose, model.x, model.y, model.z);
			Vector3d out = new Vector3d(at).sub(HitLocator.toWorld(pose, 0, 12, 0)).normalize(0.12).add(0, 0.12, 0);
			int count = Math.max(1, (int) Math.round(2 * ClientConfig.get().getParticleAmount()));
			RandomSource random = level.getRandom();
			for (int i = 0; i < count; i++) {
				level.addParticle(random.nextInt(3) == 0 ? BLOOD_DARK : BLOOD, at.x, at.y, at.z,
						out.x + random.nextGaussian() * 0.04, out.y + random.nextDouble() * 0.06, out.z + random.nextGaussian() * 0.04);
			}
		}
	}
}
