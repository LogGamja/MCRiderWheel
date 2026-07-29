package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.Minecraft;

// SDL raw 축 값을 WheelProfile로 정규화한 런타임 상태. 전부 SDL 기반이고 GLFW는 안 쓴다
public class WheelInput {
	public static boolean available;
	public static String activeDeviceName = "";
	public static WheelProfile activeProfile;
	public static String activeGuid;
	public static float steering; // -1 (좌) .. 1 (우)
	public static float throttle; // 0 .. 1
	public static float brake;    // 0 .. 1
	// 0 = 설정된 락 범위 이내, 1 = 물리적 끝단. 락이 물리 범위보다 좁을 때만 0보다 커진다
	public static float steerOverTravel;

	// 일부 페달은 재연결 직후 한 번 움직이기 전까지 어중간한 raw 값을 보고하는데
	// 그 값이 released 쪽 극단이면 정규화 시 절반 스로틀로 읽혀서 문제가 된다
	private static final PedalArming throttleArming = new PedalArming();
	private static final PedalArming brakeArming = new PedalArming();
	// 위 arming 상태가 어느 GUID 것인지 - 휠을 바꾸면 다시 arm해야 한다
	private static String armedForGuid;

	// 페달 축이 "한 번이라도 실제 값을 보고했는지" 상태를 들고 있는다.
	// released로 판정되거나, 처음 값에서 조금이라도 움직이면 armed 된다
	private static final class PedalArming {
		private static final float RELEASED_MAX = 0.1f;
		private static final float LIVE_MOVE_EPSILON = 0.02f;

		private int axis = -1;
		private boolean armed;
		private boolean firstSampleSeen;
		private float firstRaw;

		void reset() {
			armed = false;
			firstSampleSeen = false;
			axis = -1;
		}

		float apply(int axis, float normalized, float raw) {
			// 재보정으로 GUID는 그대로인데 축만 바뀔 수 있다
			if (axis != this.axis) {
				reset();
				this.axis = axis;
			}
			if (!armed) {
				if (!firstSampleSeen) {
					firstSampleSeen = true;
					firstRaw = raw;
				}
				armed = normalized <= RELEASED_MAX || Math.abs(raw - firstRaw) > LIVE_MOVE_EPSILON;
			}
			return armed ? normalized : 0f;
		}
	}

	public static void tick() {
		// 보정 화면이 같은 SDL 핸들을 놓고 이 메서드와 경쟁하면 초당 20번씩
		// 다른 GUID로 재오픈하게 되어 DirectInput이 멈출 수 있다. 주행/FFB는
		// 보정 화면이 떠있는 동안 이미 다 억제되므로 여기서도 그냥 상태를 얼려둔다
		if (Minecraft.getInstance().screen instanceof WheelCalibrationScreen) {
			return;
		}

		available = false;
		int count = SdlJoystickReader.deviceCount();

		// 선호 장치를 먼저 시도하고 나머지는 SDL 열거 순서대로. isDriveable()로
		// 조향 보정 실패한 프로필이 자리를 차지해 다른 정상 휠을 막는 걸 방지한다
		java.util.List<String> candidates = new java.util.ArrayList<>();
		String preferredGuid = WheelConfig.getPreferredGuid();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null) continue;
			WheelProfile profile = WheelConfig.get(guid);
			if (profile == null || !profile.isDriveable()) continue;
			if (preferredGuid != null && preferredGuid.equalsIgnoreCase(guid)) {
				candidates.add(0, guid);
			} else {
				candidates.add(guid);
			}
		}

		for (String guid : candidates) {
			WheelProfile profile = WheelConfig.get(guid);

			// 일시적인 open() 실패는 다음 틱 재시도로 충분히 회복된다
			if (!SdlJoystickReader.open(guid)) continue;
			SdlJoystickReader.update();

			if (!java.util.Objects.equals(guid, armedForGuid)) {
				throttleArming.reset();
				brakeArming.reset();
				armedForGuid = guid;
			}

			available = true;
			activeDeviceName = profile.name;
			activeProfile = profile;
			activeGuid = guid;
			steering = mapSteer(profile);
			steerOverTravel = computeOverTravel(profile);
			throttle = mapPedal(profile.throttleAxis, profile.throttleReleased, profile.throttlePressed, throttleArming);
			brake = mapPedal(profile.brakeAxis, profile.brakeReleased, profile.brakePressed, brakeArming);
			break;
		}

		if (!available) {
			// SdlJoystickReader.close()는 호출하지 않는다 - 보정 화면이 같은 핸들을
			// 쓰고 있을 수 있어서 여기서 닫으면 보정 도중인 장치가 끊긴다
			activeDeviceName = "";
			activeProfile = null;
			activeGuid = null;
			steering = 0f;
			throttle = 0f;
			brake = 0f;
			steerOverTravel = 0f;
			throttleArming.reset();
			brakeArming.reset();
			armedForGuid = null;
		}
	}

	// tick()이 20Hz로 캐시한 steering 대신 SDL 축을 즉시 다시 읽는다
	// (WheelDrivingControl.tickRotation()이 프레임마다 호출)
	public static float steeringNow() {
		if (!available || activeProfile == null) return 0f;
		SdlJoystickReader.update();
		return mapSteer(activeProfile);
	}

	private static float mapSteer(WheelProfile p) {
		if (p.steerAxis < 0 || p.steerAxis >= SdlJoystickReader.axisCount()) return 0f;
		float v = SdlJoystickReader.axisValue(p.steerAxis);
		float c = p.steerCenter;
		if (isTowardRight(v, p)) {
			float span = p.steerRight - c;
			return Math.abs(span) < 1e-4f ? 0f : clamp((v - c) / span, -1f, 1f);
		} else {
			float span = c - p.steerLeft;
			return Math.abs(span) < 1e-4f ? 0f : clamp((v - c) / span, -1f, 1f);
		}
	}

	// v가 steerCenter 기준으로 steerRight와 같은 쪽에 있는지 판정한다.
	// steerCenter가 두 락의 정중앙이 아닐 수도 있어서, 부호를 곱해서 판정한다
	// (거리 비교 방식은 축 극성엔 안전하지만 경계가 steerCenter가 아니라 중점이 되어
	// 비대칭 보정에서 mapSteer() 출력이 불연속으로 튈 수 있었다)
	private static boolean isTowardRight(float v, WheelProfile p) {
		return (v - p.steerCenter) * (p.steerRight - p.steerCenter) >= 0f;
	}

	// 소프트락 벽은 설정된 락 이후 이 각도만큼에서 최대 강도에 도달한다
	// (남은 물리 범위 전체에 걸쳐 서서히 세지는 게 아니라 락 지점에서 바로 세져야 벽처럼 느껴진다)
	private static final float WALL_WIDTH_DEG = 8f;
	private static final float DEFAULT_WALL_WIDTH_RAW = 0.05f; // steerRawPerDegree를 모를 때 폴백

	private static float computeOverTravel(WheelProfile p) {
		if (p.steerAxis < 0 || p.steerAxis >= SdlJoystickReader.axisCount()) return 0f;
		float v = SdlJoystickReader.axisValue(p.steerAxis);
		float c = p.steerCenter;
		float lockRaw;
		float physRaw;
		if (isTowardRight(v, p)) {
			lockRaw = Math.abs(p.steerRight - c);
			physRaw = Math.abs(p.steerPhysicalRight - c);
		} else {
			lockRaw = Math.abs(p.steerLeft - c);
			physRaw = Math.abs(p.steerPhysicalLeft - c);
		}
		float travel = Math.abs(v - c);
		float overRange = physRaw - lockRaw;
		if (overRange < 1e-4f) return 0f; // 설정된 락이 이미 물리적 한계라 더 돌 여지가 없음

		float wallWidth = p.steerRawPerDegree > 1e-6f ? WALL_WIDTH_DEG * p.steerRawPerDegree : DEFAULT_WALL_WIDTH_RAW;
		wallWidth = Math.min(wallWidth, overRange);
		return clamp((travel - lockRaw) / wallWidth, 0f, 1f);
	}

	private static float mapPedal(int axis, float released, float pressed, PedalArming arming) {
		if (axis < 0 || axis >= SdlJoystickReader.axisCount()) return 0f;
		float v = SdlJoystickReader.axisValue(axis);
		float span = pressed - released;
		if (Math.abs(span) < 1e-4f) return 0f;
		return arming.apply(axis, clamp((v - released) / span, 0f, 1f), v);
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
