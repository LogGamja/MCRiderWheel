package com.mcrider.mcriderwheel.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores one calibrated {@link WheelProfile} per device GUID so a wheel
 * only needs to be calibrated once, regardless of USB port/reconnects, plus
 * which GUID (if any) the player has explicitly chosen to drive with when
 * more than one calibrated wheel is connected at once (see
 * WheelDeviceSelectScreen and WheelInput's own use of getPreferredGuid()).
 */
public class WheelConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcriderwheel-config.json");
	private static Map<String, WheelProfile> profiles;
	private static String preferredGuid;

	/**
	 * Every caller elsewhere in the mod compares GUIDs with equalsIgnoreCase
	 * (SdlJoystickReader.open(), WheelInput's preferred-device match,
	 * WheelForceFeedback's device lookups) since SDL's own GUID string casing
	 * isn't guaranteed stable. This map's keys - and preferredGuid - are
	 * normalized to lowercase on the way in so a lookup here can just be a
	 * plain HashMap get() instead of every caller needing its own
	 * case-insensitive scan.
	 */
	private static String normalize(String guid) {
		return guid == null ? null : guid.toLowerCase(java.util.Locale.ROOT);
	}

	private static void putAllNormalized(Map<String, WheelProfile> loaded) {
		if (loaded == null) return;
		for (Map.Entry<String, WheelProfile> entry : loaded.entrySet()) {
			profiles.put(normalize(entry.getKey()), entry.getValue());
		}
	}

	private static void ensureLoaded() {
		if (profiles != null) return;
		profiles = new HashMap<>();
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				JsonObject root = GSON.fromJson(r, JsonObject.class);
				if (root != null) {
					Type profilesType = new TypeToken<Map<String, WheelProfile>>() {}.getType();
					if (root.has("profiles")) {
						// Current format: {"profiles": {guid: profile, ...}, "preferredGuid": "..."}.
						Map<String, WheelProfile> loaded = GSON.fromJson(root.get("profiles"), profilesType);
						putAllNormalized(loaded);
						if (root.has("preferredGuid") && !root.get("preferredGuid").isJsonNull()) {
							preferredGuid = normalize(root.get("preferredGuid").getAsString());
						}
					} else {
						// Pre-existing files saved before preferredGuid was added: the
						// root object itself is the guid->profile map with no wrapper.
						Map<String, WheelProfile> loaded = GSON.fromJson(root, profilesType);
						putAllNormalized(loaded);
					}
				}
			} catch (Exception e) {
				// Not just IOException: a truncated/hand-edited file makes Gson
				// throw JsonSyntaxException, which would otherwise propagate out
				// of the client tick and crash the game instead of just losing
				// the saved profiles.
				WheelClientMain.LOGGER.warn("Failed to load wheel config", e);
			}
		}
	}

	public static WheelProfile get(String guid) {
		ensureLoaded();
		return guid == null ? null : profiles.get(normalize(guid));
	}

	public static void save(WheelProfile profile) {
		ensureLoaded();
		profiles.put(normalize(profile.guid), profile);
		writeToDisk();
	}

	/** GUID the player explicitly picked to drive with (see WheelDeviceSelectScreen), or null to just use whichever calibrated device SDL enumerates first (the original behavior). */
	public static String getPreferredGuid() {
		ensureLoaded();
		return preferredGuid;
	}

	public static void setPreferredGuid(String guid) {
		ensureLoaded();
		preferredGuid = normalize(guid);
		writeToDisk();
	}

	private static void writeToDisk() {
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject root = new JsonObject();
			root.add("profiles", GSON.toJsonTree(profiles));
			if (preferredGuid != null) {
				root.addProperty("preferredGuid", preferredGuid);
			}
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(root, w);
			}
		} catch (IOException e) {
			WheelClientMain.LOGGER.warn("Failed to save wheel config", e);
		}
	}
}
