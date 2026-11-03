package ru.deelter.chopchop.core.profile;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The body profiles there are. Built into the jar as {@code chopchop/profiles/<id>.json}, listed in
 * {@code chopchop/profiles/index.json}. The mod and the plugin read the same files, so a server and a client agree
 * on every part and grid.
 * <p>
 * An entity type that no profile names gets no wounds at all: there is no guessing of a layout.
 */
public final class Profiles {
	private static final Gson GSON = new Gson();
	private static final String ROOT = "/chopchop/profiles/";

	private final Map<String, BodyProfile> byId = new LinkedHashMap<>();
	private final Map<String, BodyProfile> byEntity = new HashMap<>();

	public static Profiles loadBuiltin() {
		Profiles profiles = new Profiles();
		String[] ids;
		try (Reader reader = open(ROOT + "index.json")) {
			ids = GSON.fromJson(reader, String[].class);
		} catch (IOException e) {
			throw new IllegalStateException("No profile index in the jar", e);
		}
		for (String id : ids) {
			try (Reader reader = open(ROOT + id + ".json")) {
				profiles.add(GSON.fromJson(reader, BodyProfile.class));
			} catch (IOException | RuntimeException e) {
				throw new IllegalStateException("Broken body profile " + id, e);
			}
		}
		return profiles;
	}

	public static BodyProfile parse(Reader reader) {
		BodyProfile profile = GSON.fromJson(reader, BodyProfile.class);
		profile.init();
		return profile;
	}

	private static Reader open(String path) throws IOException {
		InputStream in = Profiles.class.getResourceAsStream(path);
		if (in == null) throw new IOException("missing " + path);
		return new InputStreamReader(in, StandardCharsets.UTF_8);
	}

	public void add(BodyProfile profile) {
		profile.init();
		byId.put(profile.getId(), profile);
		for (String entity : profile.getEntities()) byEntity.put(entity, profile);
	}

	/**
	 * Lets a server turn a type off, or give it another profile, from its config.
	 */
	public void assign(String entityType, String profileId) {
		if (profileId == null || profileId.isEmpty()) {
			byEntity.remove(entityType);
			return;
		}
		BodyProfile profile = byId.get(profileId);
		if (profile != null) byEntity.put(entityType, profile);
	}

	public BodyProfile byId(String id) {
		return byId.get(id);
	}

	public BodyProfile forEntity(String entityType) {
		return byEntity.get(entityType);
	}

	public Collection<BodyProfile> all() {
		return Collections.unmodifiableCollection(byId.values());
	}
}
