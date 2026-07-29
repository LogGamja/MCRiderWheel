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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores one calibrated {@link WheelProfile} per device GUID so a wheel
 * only needs to be calibrated once, regardless of USB port/reconnects, plus
 * which GUID (if any) should be preferred to drive with when more than one
 * calibrated wheel is connected at once (see WheelInput's own use of
 * getPreferredGuid()). Set either by explicitly picking a device in
 * WheelDeviceSelectScreen, or automatically whenever a device finishes (or
 * re-finishes) any part of calibration (see WheelCalibrationScreen's
 * completeAndSave()/persistProgress()) - a wheel someone just calibrated is
 * almost always the one they want driving now.
 */
public class WheelConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcriderwheel-config.json");
	private static Map<String, WheelProfile> profiles;
	private static String preferredGuid;
	// GUIDs the player has dismissed the auto-calibration wizard for (see
	// WheelCalibrationScreen's autoTriggered handling) without finishing it -
	// without this, an uncalibrated gamepad/second device left plugged in
	// re-prompts on every single world join/hotplug forever, since nothing
	// else ever marks it as "already asked about this one".
	private static java.util.Set<String> declinedAutoCalibrationGuids;

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
		declinedAutoCalibrationGuids = new java.util.HashSet<>();
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
						if (root.has("declinedAutoCalibration")) {
							Type guidSetType = new TypeToken<java.util.Set<String>>() {}.getType();
							java.util.Set<String> loadedDeclined = GSON.fromJson(root.get("declinedAutoCalibration"), guidSetType);
							if (loadedDeclined != null) {
								for (String guid : loadedDeclined) {
									declinedAutoCalibrationGuids.add(normalize(guid));
								}
							}
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
				// profiles is whatever partial state survived the failed parse
				// (possibly empty) - the very next save() call (e.g. right after
				// calibrating) overwrites this file with just that, permanently
				// losing any profile the failed parse didn't recover. Backing up
				// the unreadable file here, before anything can write over it,
				// is what actually saves it - not a JSON-repair attempt, just a
				// copy the player can hand-recover from.
				backupUnreadableFile();
			}
		}
	}

	/** Best-effort copy of the unparseable config file to a sibling .bak, so a failed load doesn't just quietly vanish once the next save() overwrites the original. */
	private static void backupUnreadableFile() {
		try {
			Path backup = FILE.resolveSibling(FILE.getFileName() + ".bak");
			Files.copy(FILE, backup, StandardCopyOption.REPLACE_EXISTING);
			WheelClientMain.LOGGER.warn("Backed up unreadable wheel config to {}", backup);
		} catch (IOException e) {
			WheelClientMain.LOGGER.warn("Failed to back up unreadable wheel config", e);
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

	/** GUID to prefer driving with (explicitly picked via WheelDeviceSelectScreen, or auto-set on calibration - see this class's doc comment), or null to just use whichever calibrated device SDL enumerates first (the original behavior). */
	public static String getPreferredGuid() {
		ensureLoaded();
		return preferredGuid;
	}

	public static void setPreferredGuid(String guid) {
		ensureLoaded();
		preferredGuid = normalize(guid);
		writeToDisk();
	}

	/** Whether the player has already dismissed the auto-calibration wizard for this device without finishing it - see WheelCalibrationScreen's autoTriggered handling. */
	public static boolean isAutoCalibrationDeclined(String guid) {
		ensureLoaded();
		return guid != null && declinedAutoCalibrationGuids.contains(normalize(guid));
	}

	/** Marks this device as "already asked" so the auto-calibration wizard stops offering to calibrate it on every join/hotplug. */
	public static void declineAutoCalibration(String guid) {
		ensureLoaded();
		if (guid == null) return;
		declinedAutoCalibrationGuids.add(normalize(guid));
		writeToDisk();
	}

	/**
	 * Clears a previously-declined device's "already asked" flag - called from
	 * WheelCalibrationScreen when the player actually advances past its INTRO
	 * step for this device, i.e. a fresh, active decision to work on it now.
	 * Without this there was no way back from one accidental/changed-mind
	 * decline short of hand-editing the config file: declineAutoCalibration()
	 * is the only other place that ever touches this set, and it's one-way.
	 */
	public static void clearAutoCalibrationDecline(String guid) {
		ensureLoaded();
		if (guid == null) return;
		if (declinedAutoCalibrationGuids.remove(normalize(guid))) {
			writeToDisk();
		}
	}

	/**
	 * Writes through a sibling .tmp file plus an atomic rename rather than
	 * truncating FILE directly - this project has a documented history of the
	 * whole process dying to an uncatchable native access violation (see
	 * SdlJoystickReader/SdlForceFeedback's own doc comments), and a truncate-
	 * in-place write straddled by exactly that kind of crash would leave every
	 * saved profile permanently lost, not just the one save() call in
	 * progress. Files.move with ATOMIC_MOVE makes the rename itself all-or-
	 * nothing so FILE is always either the old complete contents or the new
	 * complete contents, never a half-written truncation.
	 */
	private static void writeToDisk() {
		Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
		try {
			Files.createDirectories(FILE.getParent());
			JsonObject root = new JsonObject();
			root.add("profiles", GSON.toJsonTree(profiles));
			if (preferredGuid != null) {
				root.addProperty("preferredGuid", preferredGuid);
			}
			if (!declinedAutoCalibrationGuids.isEmpty()) {
				root.add("declinedAutoCalibration", GSON.toJsonTree(declinedAutoCalibrationGuids));
			}
			try (Writer w = Files.newBufferedWriter(tmp)) {
				GSON.toJson(root, w);
			}
			try {
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				// Same-filesystem atomic rename isn't guaranteed on every
				// platform/filesystem combination - a non-atomic replace here
				// is still strictly better than the old always-truncate path
				// (the window where FILE could end up truncated shrinks to
				// just this fallback move itself, not the whole JSON
				// serialization+write above it).
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			WheelClientMain.LOGGER.warn("Failed to save wheel config", e);
		}
	}
}
