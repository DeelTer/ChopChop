package ru.deelter.chopchop.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.Setter;
import net.fabricmc.loader.api.FabricLoader;
import ru.deelter.chopchop.ChopChop;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code config/chopchop-client.json}: how this client draws what the server decided. Nothing here changes the
 * wounds themselves.
 */
@Getter
@Setter
public final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ClientConfig instance = new ClientConfig();

	private boolean enabled = true;
	private boolean particles = false;
	/**
	 * multiplies the number of particles
	 */
	private double particleAmount = 1.0;
	private boolean sounds = true;
	private double soundVolume = 1.0;
	private boolean debris = true;
	private int debrisSeconds = 18;
	/**
	 * dead mobs lie on the ground as bodies, as long as the server says (needs the server to send them)
	 */
	private boolean corpses = true;
	/**
	 * pieces on the ground are kicked about by players walking into them
	 */
	private boolean debrisKick = true;
	private boolean bones = true;
	/**
	 * the bones of a vanilla skeleton inside humanoid bodies: a deep wound shows the skull, the ribs, the spine
	 */
	private boolean skeletonInside = true;
	/**
	 * a hand that is gone uses nothing, a leg that is gone does not sprint, digging takes whatever limb is left
	 */
	private boolean limbRules = true;
	private boolean firstPerson = true;
	private boolean hideItems = true;
	private int maxDistance = 64;
	/**
	 * pieces of bodies lying about at once, the oldest goes first
	 */
	private int maxDebris = 48;
	/**
	 * dead bodies lying about at once, the oldest goes first
	 */
	private int maxCorpses = 48;
	/**
	 * farther than this, blocks, a wound shows meat only, not the bones of the skeleton inside (cheaper)
	 */
	private int skeletonDistance = 24;
	/**
	 * the camera rides in your head when it comes off
	 */
	private boolean headCam = true;
	/**
	 * seconds of the head cam before the death screen comes up
	 */
	private double headCamSeconds = 5.0;
	/**
	 * blood squirting from fresh stumps
	 */
	private boolean fountains = true;
	/**
	 * when you take a head, the camera swings around it for a moment
	 */
	/**
	 * after death the camera leaves the eyes and looks at the own body lying there
	 */
	private boolean ownCorpse = true;
	/**
	 * armour shows the wounds under it as holes; off, armour only loses what is cut off
	 */
	private boolean woundsThroughArmor = true;
	private boolean killCam = true;
	private double killCamSeconds = 2.5;

	public static ClientConfig get() {
		return instance;
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("chopchop-client.json");
	}

	public static void load() {
		Path file = file();
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				ClientConfig read = GSON.fromJson(reader, ClientConfig.class);
				if (read != null) instance = read;
			} catch (IOException | RuntimeException e) {
				ChopChop.LOG.error("Can't read {}, using the defaults", file, e);
			}
		}
		instance.clamp();
		save();
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(file())) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			ChopChop.LOG.warn("Can't write {}", file(), e);
		}
	}

	private void clamp() {
		particleAmount = Math.clamp(particleAmount, 0, 4);
		soundVolume = Math.clamp(soundVolume, 0, 2);
		debrisSeconds = Math.clamp(debrisSeconds, 1, 60);
		maxDistance = Math.clamp(maxDistance, 8, 256);
		maxDebris = Math.clamp(maxDebris, 1, 256);
		maxCorpses = Math.clamp(maxCorpses, 1, 256);
		skeletonDistance = Math.clamp(skeletonDistance, 0, 256);
		headCamSeconds = Math.clamp(headCamSeconds, 0, 15);
		killCamSeconds = Math.clamp(killCamSeconds, 0.5, 6);
	}
}
