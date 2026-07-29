package com.mcrider.mcriderwheel.client.ffb;

import com.mcrider.mcriderwheel.client.WheelCalibrationScreen;
import com.mcrider.mcriderwheel.client.WheelInput;
import com.mcrider.mcriderwheel.client.WheelProfile;
import com.mcrider.mcriderwheel.client.WheelClientMain;
import com.mcrider.mcriderwheel.client.sdl.SdlForceFeedback;
import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.Minecraft;

// 휠이 중앙에서 멀어질수록 세게 되돌아오는 센터링 힘을 낸다.
public final class WheelForceFeedback {
	// 락 범위 안에서의 센터링 강도. 1 미만이어야 소프트락 벽이 더할 여지가 남는다
	private static final float STRENGTH = 0.5f;
	// 이동 방향과 MCRider 방향 표시 엔티티가 어긋날 때 추가되는 저항
	private static final float EXTRA_RESISTANCE_BOOST = 0.2f;
	// FFB 강도 100%를 넘으면 순수 센터링만으로 출력 상한에 도달해버려서 벽이
	// 더할 게 없어진다. 이걸 막기 위해 순수 센터링이 쓸 수 있는 몫을 이 비율로 제한한다
	// (100% 이하에서는 원래도 이 값을 안 넘어서 영향 없음)
	private static final float CENTER_MAGNITUDE_BUDGET = 0.85f;

	private static final int PULSE_PERIOD_MS = 40; // 약 25Hz 진동, 장치 자체가 렌더링

	private static boolean sdlAvailable;
	private static boolean enabled;
	private static int handle = -1;
	private static boolean forceLoggedOnce = true;
	// 창 포커스를 잃으면 DirectInput이 조용히 장치를 놓아버려서 재확보를 위해 재오픈이 필요하다
	private static boolean wasWindowActive = true;
	// enable()이 열지 못한 GUID - FFB 미지원 장치에서 매 틱 재시도/재로그하지 않기 위함
	private static String failedGuid;
	// 현재 열린 핸들이 실제로 어느 GUID인지 - 없으면 휠을 바꿔도 힘 피드백이 옛 장치로 계속 나간다
	private static String enabledGuid;
	private static volatile boolean extraResistance;
	// TireGripFeedback이 설정: 그립 손실 진동 강도. pulse()가 아니라 센터링과 같은
	// 상시 constant-force 채널에서 진동으로 렌더링한다 (pulse는 매 틱 재호출하면 서로 죽인다)
	private static volatile float gripVibrationMagnitude;
	// TireGripFeedback이 설정: 타이어가 실제로 미끄러진 상태(state-drift)인지.
	// true면 센터링은 완전히 빼고 소프트락 벽과 진동만 남긴다
	private static volatile boolean tiresBrokenLoose;
	private static int vibrationPhaseTicks;
	private static final int VIBRATION_HALF_PERIOD_TICKS = 1; // 20Hz 틱 기준 약 10Hz 사각파
	// mcrider_ffb_pulse()를 짧은 시간 안에 여러 번 부르면 JVM이 죽는 걸 확인해서 쿨다운을 둔다
	private static final long PULSE_COOLDOWN_NANOS = 150_000_000L; // 펄스 지속시간(150ms)과 동일
	private static long lastPulseNanos;
	// System.nanoTime()이 항상 양수라는 보장이 없어서 "한 번이라도 쐈는지"는 별도 플래그로 관리
	private static boolean everPulsed;
	// 네이티브 호출이 실제로 크래시하면 잠재적으로 손상된 라이브러리를 다시 건드리지 않고
	// 이번 세션 동안 FFB를 완전히 꺼버린다
	private static volatile boolean nativeFaulted;
	// SdlJoystickReader가 버튼/축용으로 이미 장치를 열어둔 상태에서, 여기서 또
	// 별도 핸들로 haptic을 열면 장치가 나타난 직후엔 raw 액세스 위반으로 크래시했다.
	// 장치가 "사용 가능" 상태로 잠시 안정된 뒤에 여는 게 완화책이다
	private static final long ENABLE_ARM_DELAY_NANOS = 1_000_000_000L;
	private static long availableSinceNanos = -1; // -1 = 현재 사용 불가
	private static String armedGuid; // availableSinceNanos가 재는 대상 GUID

	private WheelForceFeedback() {
	}

	// 첫 실패만 로그로 남기기 위한 플래그
	private static boolean sdlInitFailureLogged;

	public static void init() {
		// libsdl4j의 SDL2 네이티브 라이브러리를 지금 로드해서 나중에 enable()에서
		// 놀라지 않게 한다. SdlForceFeedback의 SDL_InitSubSystem 호출을 겸한다
		ensureSdlAvailable();
	}

	// 초기화 실패해도 latch하지 않고 매 틱 재시도한다 (nativeFaulted가 뜨기 전까지)
	private static boolean ensureSdlAvailable() {
		if (sdlAvailable) return true;
		try {
			if (SdlForceFeedback.mcrider_ffb_init() == 0) {
				sdlAvailable = true;
				return true;
			}
		} catch (Throwable t) {
			if (!sdlInitFailureLogged) {
				sdlInitFailureLogged = true;
				WheelClientMain.LOGGER.error("[FFB] failed to load/initialize SDL force feedback", t);
			}
			return false;
		}
		if (!sdlInitFailureLogged) {
			sdlInitFailureLogged = true;
			WheelClientMain.LOGGER.error("[FFB] failed to initialize SDL force feedback: {}", SdlForceFeedback.mcrider_ffb_last_error());
		}
		return false;
	}

	public static void setExtraResistance(boolean active) {
		extraResistance = active;
	}

	public static void setGripVibrationMagnitude(float magnitude) {
		gripVibrationMagnitude = Math.max(0f, Math.min(1f, magnitude));
	}

	public static void setTiresBrokenLoose(boolean broken) {
		tiresBrokenLoose = broken;
	}

	private static void enable() {
		WheelProfile profile = WheelInput.activeProfile;
		if (profile == null) return;

		try {
			SdlForceFeedback.mcrider_ffb_init();
			int index = findSdlIndexForGuid(profile.guid);
			if (index < 0) {
				WheelClientMain.LOGGER.warn("[FFB] could not match device '{}' (guid {}) to any SDL haptic device", profile.name, profile.guid);
				int count = SdlForceFeedback.mcrider_ffb_device_count();
				for (int i = 0; i < count; i++) {
					WheelClientMain.LOGGER.warn("[FFB]   SDL device {}: name='{}' guid={}", i,
							SdlForceFeedback.mcrider_ffb_device_name(i), SdlForceFeedback.mcrider_ffb_device_guid(i));
				}
				failedGuid = profile.guid;
				return;
			}

			handle = SdlForceFeedback.mcrider_ffb_open(index);
			if (handle < 0) {
				WheelClientMain.LOGGER.warn("[FFB] failed to open device for force feedback: {}", SdlForceFeedback.mcrider_ffb_last_error());
				failedGuid = profile.guid;
				return;
			}

			enabled = true;
			enabledGuid = profile.guid;
			failedGuid = null;
			int caps = SdlForceFeedback.mcrider_ffb_query(handle);
			int axes = SdlForceFeedback.mcrider_ffb_num_axes(handle);
			WheelClientMain.LOGGER.info("[FFB] enabled on device index {} (handle {}) caps=0x{} axes={} (SDL_HAPTIC_CONSTANT bit={})",
					index, handle, Integer.toHexString(caps), axes, (caps & 0x0001) != 0);
			forceLoggedOnce = false;
			restartTicks = 0;
			wasNearCenter = true;
		} catch (Throwable t) {
			onNativeFault("enable", t);
		}
	}

	// onNativeFault를 거치지 않는다 - disable() 자체의 실패가 다시 onNativeFault로 돌면
	// disable()이 무한 재귀할 수 있다
	private static void disable(String reason) {
		if (enabled) {
			WheelClientMain.LOGGER.info("[FFB] disabling force feedback ({})", reason);
		}
		enabled = false;
		enabledGuid = null;
		failedGuid = null;
		stoppedForCalibration = false;
		// 다음 open이 또 20Hz open/close storm이 되지 않도록 안정화 대기를 다시 건다
		availableSinceNanos = System.nanoTime();
		if (sdlAvailable && handle >= 0) {
			try {
				SdlForceFeedback.mcrider_ffb_stop(handle);
				SdlForceFeedback.mcrider_ffb_close(handle);
			} catch (Throwable t) {
				nativeFaulted = true;
				WheelClientMain.LOGGER.error("[FFB] native call 'disable' crashed, disabling force feedback for the rest of this session", t);
			}
		}
		handle = -1;
	}

	// SdlJoystickReader와 SdlForceFeedback은 각자 따로 열거하므로 인덱스가 아니라 GUID로 매칭한다
	private static int findSdlIndexForGuid(String guid) {
		if (guid == null || !sdlAvailable) return -1;
		int count = SdlForceFeedback.mcrider_ffb_device_count();
		for (int i = 0; i < count; i++) {
			if (guid.equalsIgnoreCase(SdlForceFeedback.mcrider_ffb_device_guid(i))) {
				return i;
			}
		}
		return -1;
	}

	// 착지/충돌 충격을 흉내내는 단발성 킥. 20Hz 틱에서 흔드는 게 아니라 장치 자체
	// 타이밍의 SDL_HAPTIC_SINE 이펙트로 처리해야 방향이 한쪽으로만 쏠리지 않는다.
	// 킥의 첫 반주기는 현재 조향 방향과 같은 쪽으로 향한다 (센터링과는 반대 부호)
	public static void pulse(float magnitude, long durationMs) {
		if (nativeFaulted || !enabled || !sdlAvailable || handle < 0) {
			return;
		}
		// 보정 화면에서 손으로 기준점을 잡고 있는 동안은 충격 킥으로 방해하지 않는다
		if (Minecraft.getInstance().screen instanceof WheelCalibrationScreen) {
			return;
		}

		long now = System.nanoTime();
		if (everPulsed && now - lastPulseNanos < PULSE_COOLDOWN_NANOS) {
			return;
		}
		everPulsed = true;
		lastPulseNanos = now;

		int startSign = WheelInput.steering >= 0 ? -1 : 1;
		float scaledMagnitude = Math.min(1f, magnitude * strengthMultiplier());
		try {
			int result = SdlForceFeedback.mcrider_ffb_pulse(handle, scaledMagnitude, PULSE_PERIOD_MS, (int) durationMs, startSign);
			if (result != 0) {
				WheelClientMain.LOGGER.warn("[FFB] pulse failed: {}", SdlForceFeedback.mcrider_ffb_last_error());
			}
		} catch (Throwable t) {
			onNativeFault("pulse", t);
		}
	}

	// 장치 전환 직전에 haptic 핸들을 미리 닫는다. WheelInput.tick()이 조이스틱
	// 핸들을 먼저 바꿔버리는 것보다 앞서 실행되도록 전환을 시작하는 쪽에서 호출한다
	public static void releaseForDeviceChange() {
		if (enabled) disable("device change requested");
	}

	private static void onNativeFault(String call, Throwable t) {
		nativeFaulted = true;
		WheelClientMain.LOGGER.error("[FFB] native call '{}' crashed, disabling force feedback for the rest of this session", call, t);
		disable("native fault in " + call);
	}

	// 예전엔 슬라이더를 150%까지 올려야 힘이 느껴졌어서, 모든 설정값의 실제 출력에 이 배율을 곱한다
	private static final float STRENGTH_BOOST = 1.5f;

	private static float strengthMultiplier() {
		WheelProfile profile = WheelInput.activeProfile;
		float percent = profile != null ? profile.ffbStrengthPercent : 100f;
		return percent / 100f * STRENGTH_BOOST;
	}

	// 힘 피드백은 별도 켜기/끄기가 없고 계산된 휠이 있으면 자동으로 열린다
	public static void tick() {
		if (nativeFaulted || !ensureSdlAvailable()) return;

		boolean windowActive = Minecraft.getInstance().isWindowActive();
		if (windowActive && !wasWindowActive && enabled) {
			// 완전한 재오픈은 느린 DirectInput 왕복이라, 포커스가 잠깐 깜빡인 경우를
			// 위해 먼저 저렴한 재시작을 시도하고, 그게 실패할 때만 완전히 재오픈한다
			try {
				if (handle >= 0 && SdlForceFeedback.mcrider_ffb_restart_effect(handle) != 0) {
					disable("window focus regained, reopening so DirectInput reacquires");
				} else {
					// restart_effect가 이펙트를 재생시켜서 보정 화면용 stop()을 무효화하므로
					// 이번 틱에 다시 stop이 걸리도록 플래그를 재설정한다
					stoppedForCalibration = false;
				}
			} catch (Throwable t) {
				onNativeFault("restart_effect (focus regain)", t);
			}
		}
		wasWindowActive = windowActive;

		WheelProfile profile = WheelInput.activeProfile;
		// 이미 열려있던 핸들의 대상 장치가 바뀌었으면 닫아서 아래에서 새로 연다
		// (GUID 비교는 항상 equalsIgnoreCase - 대소문자 차이만으로 매 틱 재오픈 storm이 나면 안 된다)
		if (enabled && enabledGuid != null && !enabledGuid.equalsIgnoreCase(WheelInput.activeGuid)) {
			disable("active wheel changed to " + WheelInput.activeGuid);
		}
		boolean alreadyFailed = profile != null && profile.guid != null && profile.guid.equalsIgnoreCase(failedGuid);
		if (WheelInput.available) {
			// 대상 장치가 바뀔 때마다 안정화 대기 창을 다시 잰다 (전환도 연결만큼 SDL 재열거를 유발함)
			if (availableSinceNanos < 0 || !java.util.Objects.equals(armedGuid, WheelInput.activeGuid)) {
				availableSinceNanos = System.nanoTime();
				armedGuid = WheelInput.activeGuid;
			}
			// SdlJoystickReader가 같은 장치로 실제로 안정됐는지도 함께 확인한다
			boolean readerSettled = WheelInput.activeGuid != null
					&& WheelInput.activeGuid.equalsIgnoreCase(SdlJoystickReader.openGuid());
			boolean settled = System.nanoTime() - availableSinceNanos >= ENABLE_ARM_DELAY_NANOS;
			// 보정 화면이 떠있는 동안은 WheelInput이 상태를 얼려두므로 available이
			// 그대로 true일 수 있다 - 재보정 중인 장치에 별도 haptic 핸들을 여는 걸 막는다
			boolean calibrating = Minecraft.getInstance().screen instanceof WheelCalibrationScreen;
			if (settled && readerSettled && !calibrating && !enabled && !alreadyFailed) {
				enable();
			}
		} else {
			failedGuid = null;
			if (enabled) disable("wheel no longer available");
			availableSinceNanos = -1;
		}

		if (!enabled || handle < 0) return;

		// 보정 중에 손으로 기준점을 잡고 있는 동안은 힘으로 방해하지 않는다.
		// 화면 진입 시 한 번만 stop()을 호출하고 매 틱 반복하지 않는다 (반복 호출이 크래시 원인)
		if (Minecraft.getInstance().screen instanceof WheelCalibrationScreen) {
			if (!stoppedForCalibration) {
				try {
					SdlForceFeedback.mcrider_ffb_stop(handle);
					stoppedForCalibration = true;
				} catch (Throwable t) {
					onNativeFault("stop", t);
				}
			}
			return;
		}
		stoppedForCalibration = false;

		float steering = WheelInput.steering; // -1 (좌) .. 1 (우)

		float magnitude;
		float direction;
		if (tiresBrokenLoose) {
			// 실제로 미끄러지면 자기 정렬 토크가 거의 없으므로 센터링은 빼고
			// 소프트락 벽(기계적 끝단이라 그립과 무관)과 진동만 남긴다
			float wallMagnitude = Math.min(1f, WheelInput.steerOverTravel * strengthMultiplier());
			float wallSigned = steering >= 0 ? wallMagnitude : -wallMagnitude;

			float vibMagnitude = Math.min(1f, gripVibrationMagnitude * strengthMultiplier());
			vibrationPhaseTicks++;
			boolean phaseHigh = (vibrationPhaseTicks / VIBRATION_HALF_PERIOD_TICKS) % 2 == 0;
			float vibSigned = phaseHigh ? vibMagnitude : -vibMagnitude;
			float netSigned = Math.max(-1f, Math.min(1f, wallSigned + vibSigned));
			magnitude = Math.abs(netSigned);
			direction = netSigned >= 0 ? 0f : 180f;
		} else {
			// 아래는 처음부터 최종(배율 적용 후) 출력 공간에서 계산하고 ceiling으로 제한한다
			float mult = strengthMultiplier();
			float ceiling = Math.min(1f, mult);

			// 센터링을 부호 있는 값으로 먼저 구해서 진동의 부호 있는 스윙과 합산할 수 있게 한다
			float centerMagnitude = Math.min(ceiling * CENTER_MAGNITUDE_BUDGET, Math.abs(steering) * STRENGTH * mult);
			// 소프트락: 설정된 락을 넘으면 이 설정의 ceiling까지 세게 밀어서 벽처럼 느껴지게 한다
			float overTravel = WheelInput.steerOverTravel;
			if (overTravel > 0f) {
				centerMagnitude = centerMagnitude + (ceiling - centerMagnitude) * overTravel;
			}
			if (extraResistance) {
				centerMagnitude = Math.min(ceiling, centerMagnitude + EXTRA_RESISTANCE_BOOST * mult);
			}
			float centerSigned = steering >= 0 ? centerMagnitude : -centerMagnitude;

			if (gripVibrationMagnitude > 0f) {
				// 아직 완전히 미끄러지진 않고 XP 게이지만 줄어드는 단계 - 센터링 위에 덧씌운다
				float vibMagnitude = Math.min(1f, gripVibrationMagnitude * mult);
				vibrationPhaseTicks++;
				boolean phaseHigh = (vibrationPhaseTicks / VIBRATION_HALF_PERIOD_TICKS) % 2 == 0;
				float vibSigned = phaseHigh ? vibMagnitude : -vibMagnitude;
				float netSigned = Math.max(-1f, Math.min(1f, centerSigned + vibSigned));
				magnitude = Math.abs(netSigned);
				direction = netSigned >= 0 ? 0f : 180f;
			} else {
				magnitude = centerMagnitude;
				// 조향 반대 방향으로 밀어야 자연스럽게 센터링된다 (실측으로 확인된 부호, 함부로 뒤집지 말 것)
				direction = steering >= 0 ? 0f : 180f;
			}
		}
		try {
			int result = SdlForceFeedback.mcrider_ffb_set_force(handle, magnitude, direction);

			if (result == 0) {
				// 조향이 중앙을 지나는 순간(magnitude가 거의 0일 때) 재시작해야
				// stop+run의 짧은 끊김이 안 느껴진다. 너무 자주 재시작하지 않도록 최소 간격도 둔다
				boolean nearCenter = Math.abs(WheelInput.steering) <= CENTER_RESET_DEADZONE;
				restartTicks++;
				boolean centerCrossing = nearCenter && !wasNearCenter;
				if ((centerCrossing && restartTicks >= RESTART_MIN_INTERVAL_TICKS)
						|| restartTicks >= RESTART_FALLBACK_TICKS) {
					restartTicks = 0;
					periodicRestart();
				}
				wasNearCenter = nearCenter;
			}

			if (result != 0) {
				// DirectInput이 조용히 장치를 놓아버리면 이후 모든 set_force가 계속 실패하므로
				// 매번 로그를 남기고 재오픈을 강제해서 자동으로 재연결을 시도하게 한다
				WheelClientMain.LOGGER.warn("[FFB] set_force failed: {} - closing device so it reopens fresh", SdlForceFeedback.mcrider_ffb_last_error());
				disable("set_force failed");
			} else if (!forceLoggedOnce) {
				forceLoggedOnce = true;
				WheelClientMain.LOGGER.info("[FFB] set_force succeeded (magnitude={}, direction={})", magnitude, direction);
			}
		} catch (Throwable t) {
			onNativeFault("set_force", t);
		}
	}

	// 약 60~70초 후 힘 피드백이 조용히 죽는 문제의 실제 해결책 - UpdateEffect만으로는
	// 리셋되지 않는 펌웨어/드라이버 수준의 연속 출력 컷오프로 추정되며, Stop()+Run()이면
	// 방지된다는 걸 실측으로 확인했다. 조향이 홀딩된 채로 있어도 최소 간격/폴백 간격으로 재시작한다
	private static final float CENTER_RESET_DEADZONE = 0.05f;
	private static final int RESTART_MIN_INTERVAL_TICKS = 300; // 20Hz 기준 15초
	private static final int RESTART_FALLBACK_TICKS = 600; // 20Hz 기준 30초
	private static int restartTicks;
	private static boolean wasNearCenter = true;
	private static boolean stoppedForCalibration;

	private static void periodicRestart() {
		if (SdlForceFeedback.mcrider_ffb_restart_effect(handle) != 0) {
			WheelClientMain.LOGGER.warn("[FFB] periodic effect restart failed: {} - falling back to a full reopen", SdlForceFeedback.mcrider_ffb_last_error());
			disable("periodic effect restart failed");
		}
	}

	public static void shutdown() {
		disable("client shutting down");
		if (sdlAvailable) {
			try {
				SdlForceFeedback.mcrider_ffb_quit();
			} catch (Throwable t) {
				WheelClientMain.LOGGER.error("[FFB] native call 'quit' crashed during shutdown", t);
			}
		}
	}
}
