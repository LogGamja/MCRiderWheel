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

// GUID별 WheelProfile 저장, 선호 장치(preferredGuid), 자동 보정 거부 목록을 관리한다
public class WheelConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mcriderwheel-config.json");
	private static Map<String, WheelProfile> profiles;
	private static String preferredGuid;
	// 자동 보정 안내를 완료하지 않고 넘긴 GUID들 - 매번 다시 뜨는 걸 막기 위함
	private static java.util.Set<String> declinedAutoCalibrationGuids;

	// GUID 대소문자가 항상 같다는 보장이 없어서, 여기서 소문자로 정규화해두고
	// 모든 조회가 대소문자 무시 스캔을 각자 다시 구현할 필요가 없게 한다
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
						// 현재 포맷: {"profiles": {...}, "preferredGuid": "...", "declinedAutoCalibration": [...]}
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
						// preferredGuid가 생기기 전의 구 포맷: 루트 자체가 guid->profile 맵
						Map<String, WheelProfile> loaded = GSON.fromJson(root, profilesType);
						putAllNormalized(loaded);
					}
				}
			} catch (Exception e) {
				// 손상된 파일은 JsonSyntaxException 등으로 여기까지 안 올라오게 막고
				// 다음 save() 호출로 덮어써지기 전에 원본을 백업해둔다
				WheelClientMain.LOGGER.warn("Failed to load wheel config", e);
				backupUnreadableFile();
			}
		}
	}

	// 파싱 실패한 설정 파일을 .bak으로 복사해서 나중에 수동 복구라도 할 수 있게 한다
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
		profiles.put(normalize(profile.guid), profile.copy());
		writeToDisk();
	}

	// 명시적으로 고르거나(WheelDeviceSelectScreen) 보정을 마칠 때 자동으로 설정되는 선호 장치
	public static String getPreferredGuid() {
		ensureLoaded();
		return preferredGuid;
	}

	public static void setPreferredGuid(String guid) {
		ensureLoaded();
		preferredGuid = normalize(guid);
		writeToDisk();
	}

	public static boolean isAutoCalibrationDeclined(String guid) {
		ensureLoaded();
		return guid != null && declinedAutoCalibrationGuids.contains(normalize(guid));
	}

	public static void declineAutoCalibration(String guid) {
		ensureLoaded();
		if (guid == null) return;
		declinedAutoCalibrationGuids.add(normalize(guid));
		writeToDisk();
	}

	// 보정 위저드에서 이 장치의 INTRO 단계를 실제로 넘어갈 때 호출되어 거부 플래그를 해제한다
	public static void clearAutoCalibrationDecline(String guid) {
		ensureLoaded();
		if (guid == null) return;
		if (declinedAutoCalibrationGuids.remove(normalize(guid))) {
			writeToDisk();
		}
	}

	// tmp 파일에 쓴 다음 원자적 rename으로 교체한다 - 저장 도중 네이티브 크래시가 나도
	// 파일이 절반만 쓰인 채로 남는 일이 없도록 하기 위함
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
				// 일부 플랫폼/파일시스템에서는 원자적 rename이 안 되므로 대체 경로로 폴백
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			WheelClientMain.LOGGER.warn("Failed to save wheel config", e);
		}
	}
}
