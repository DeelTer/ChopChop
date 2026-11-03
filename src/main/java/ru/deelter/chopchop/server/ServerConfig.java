package ru.deelter.chopchop.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.Getter;
import net.fabricmc.loader.api.FabricLoader;
import ru.deelter.chopchop.ChopChop;
import ru.deelter.chopchop.core.rules.WoundRules;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code config/chopchop-server.json}: the wound rules of a Fabric server or of the singleplayer host. Written with
 * the defaults when missing, so every setting shows in it.
 */
@Getter
public final class ServerConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private WoundRules rules = new WoundRules();
	/**
	 * entity type to profile id, "" turns a type off: {"minecraft:husk": ""}
	 */
	private Map<String, String> entityProfiles = new LinkedHashMap<>();

	public static ServerConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("chopchop-server.json");
		ServerConfig config = null;
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				config = GSON.fromJson(reader, ServerConfig.class);
			} catch (IOException | RuntimeException e) {
				ChopChop.LOG.error("Can't read {}, using the defaults", file, e);
			}
		}
		if (config == null) config = new ServerConfig();
		if (config.rules == null) config.rules = new WoundRules();
		if (config.entityProfiles == null) config.entityProfiles = new LinkedHashMap<>();
		try (Writer writer = Files.newBufferedWriter(file)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			ChopChop.LOG.warn("Can't write {}", file, e);
		}
		return config;
	}
}
