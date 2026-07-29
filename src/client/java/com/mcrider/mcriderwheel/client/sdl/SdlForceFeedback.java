package com.mcrider.mcriderwheel.client.sdl;

import io.github.libsdl4j.api.Sdl;
import io.github.libsdl4j.api.SdlSubSystemConst;
import io.github.libsdl4j.api.error.SdlError;
import io.github.libsdl4j.api.haptic.SDL_Haptic;
import io.github.libsdl4j.api.haptic.SDL_HapticDirection;
import io.github.libsdl4j.api.haptic.SDL_HapticDirectionEncoding;
import io.github.libsdl4j.api.haptic.SDL_HapticEffect;
import io.github.libsdl4j.api.haptic.SDL_HapticEffectType;
import io.github.libsdl4j.api.haptic.SdlHaptic;
import io.github.libsdl4j.api.haptic.SdlHapticConst;
import io.github.libsdl4j.api.haptic.effect.SDL_HapticConstant;
import io.github.libsdl4j.api.haptic.effect.SDL_HapticPeriodic;
import io.github.libsdl4j.api.joystick.SDL_Joystick;
import io.github.libsdl4j.api.joystick.SdlJoystick;

// 예전 native/mcrider_ffb.c(JNA 연동)를 libsdl4j 직접 호출로 대체한 클래스.
// 핸들 슬롯 배열 구조와 값 설정 방식을 그대로 유지했다 - 각 필드가 왜 그렇게
// 설정됐는지는 실제 하드웨어 테스트로 확인된 내용이라 "정리"하지 않고 그대로 옮겼다.
// 크래시 방지 로직(enable 지연, pulse 쿨다운, nativeFaulted 등)은 WheelForceFeedback에 있고
// 이 클래스는 네이티브 호출부만 담당한다
public final class SdlForceFeedback {
	private static final int MAX_HANDLES = 16;

	private static final class Handle {
		SDL_Joystick joystick;
		SDL_Haptic haptic;
		int effectId = -1;
		int pulseEffectId = -1;
		// mcrider_ffb_set_force()가 레이스 내내 초당 20번 불리므로, 매번 새로
		// 만들지 않고 같은 인스턴스를 재사용해서 JNA 네이티브 메모리 할당 churn을 줄인다
		SDL_HapticDirection forceDirection;
		SDL_HapticConstant forceConstant;
		SDL_HapticEffect forceEffect;
		SDL_HapticDirection pulseDirectionObj;
		SDL_HapticPeriodic pulsePeriodicObj;
		SDL_HapticEffect pulseEffectObj;
	}

	private static final Handle[] handles = new Handle[MAX_HANDLES];
	private static boolean initialized;
	private static String lastError = "";

	private SdlForceFeedback() {
	}

	private static void captureError() {
		String err = SdlError.SDL_GetError();
		lastError = err != null ? err : "";
	}

	public static String mcrider_ffb_last_error() {
		return lastError;
	}

	// SDL_HapticQuery 비트마스크. 핸들이 잘못됐으면 0
	public static int mcrider_ffb_query(int handle) {
		Handle h = handleAt(handle);
		return h != null && h.haptic != null ? SdlHaptic.SDL_HapticQuery(h.haptic) : 0;
	}

	public static int mcrider_ffb_num_axes(int handle) {
		Handle h = handleAt(handle);
		return h != null && h.haptic != null ? SdlHaptic.SDL_HapticNumAxes(h.haptic) : -1;
	}

	public static int mcrider_ffb_init() {
		if (initialized) return 0;
		if (Sdl.SDL_InitSubSystem(SdlSubSystemConst.SDL_INIT_JOYSTICK | SdlSubSystemConst.SDL_INIT_HAPTIC) != 0) {
			captureError();
			return -1;
		}
		for (int i = 0; i < MAX_HANDLES; i++) {
			handles[i] = null;
		}
		initialized = true;
		return 0;
	}

	public static int mcrider_ffb_device_count() {
		if (!initialized) return 0;
		try {
			SdlJoystick.SDL_JoystickUpdate();
			return SdlJoystick.SDL_NumJoysticks();
		} catch (Throwable t) {
			return 0;
		}
	}

	public static String mcrider_ffb_device_name(int index) {
		if (!isValidDeviceIndex(index)) return "";
		try {
			String name = SdlJoystick.SDL_JoystickNameForIndex(index);
			return name != null ? name : "";
		} catch (Throwable t) {
			return "";
		}
	}

	// 오래된 인덱스로 GUID를 조회하면 JVM이 죽으므로 GUID 구조체는
	// SdlJoystickReader.formatJoystickGuid()로 직접 조립한다
	public static String mcrider_ffb_device_guid(int index) {
		if (!isValidDeviceIndex(index)) return null;
		try {
			return SdlJoystickReader.formatJoystickGuid(SdlJoystick.SDL_JoystickGetDeviceGUID(index));
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean isValidDeviceIndex(int index) {
		if (index < 0 || !initialized) return false;
		try {
			return index < SdlJoystick.SDL_NumJoysticks();
		} catch (Throwable t) {
			return false;
		}
	}

	// 장치의 힘 피드백 핸들을 연다. 성공하면 핸들(>= 0), 힘 피드백 미지원이면 -1
	public static int mcrider_ffb_open(int index) {
		if (!initialized) return -1;

		int slot = -1;
		for (int i = 0; i < MAX_HANDLES; i++) {
			if (handles[i] == null) {
				slot = i;
				break;
			}
		}
		if (slot < 0) return -1;

		SDL_Joystick joystick;
		try {
			joystick = SdlJoystick.SDL_JoystickOpen(index);
		} catch (Throwable t) {
			captureError();
			return -1;
		}
		if (joystick == null) {
			captureError();
			return -1;
		}

		// 이 아래에서 예외가 나면 위에서 연 joystick 핸들을 반드시 닫아야 새는 게 없다
		try {
			SDL_Haptic haptic = SdlHaptic.SDL_HapticOpenFromJoystick(joystick);
			if (haptic == null) {
				captureError();
				SdlJoystick.SDL_JoystickClose(joystick);
				return -1;
			}

			// 휠 자체의 센터링 스프링을 꺼서 Java 쪽 센터링과 안 싸우게 함, 실패해도 무시
			SdlHaptic.SDL_HapticSetAutocenter(haptic, 0);
			// gain은 건드리지 않음, 제조사 컨트롤 패널 설정과 충돌하는 걸 실측으로 확인함

			Handle h = new Handle();
			h.joystick = joystick;
			h.haptic = haptic;
			handles[slot] = h;
			return slot;
		} catch (Throwable t) {
			captureError();
			SdlJoystick.SDL_JoystickClose(joystick);
			return -1;
		}
	}

	// 지속적인 constant-force 이펙트를 설정/갱신한다
	// magnitude: 0.0 .. 1.0, directionDeg: 0..359 (SDL polar, 0 = 플레이어 반대쪽)
	public static int mcrider_ffb_set_force(int handle, float magnitude, float directionDeg) {
		Handle h = handleAt(handle);
		if (h == null || h.haptic == null) return -1;

		magnitude = Math.max(0f, Math.min(1f, magnitude));

		// 휠은 축이 하나뿐이라 DirectInput이 POLAR 방향을 거부하므로
		// CARTESIAN + 부호 있는 레벨 조합만 단일 축 휠에서 동작하는 걸 확인했다
		int sign = (directionDeg >= 90.0f && directionDeg < 270.0f) ? -1 : 1;

		// 매 틱 바뀌는 constant.level 필드만 갱신하고 나머지 구조체는 재사용한다
		if (h.forceDirection == null) {
			h.forceDirection = new SDL_HapticDirection();
			h.forceDirection.type = (byte) SDL_HapticDirectionEncoding.SDL_HAPTIC_CARTESIAN;
			h.forceDirection.dir[0] = 1;
		}

		if (h.forceConstant == null) {
			h.forceConstant = new SDL_HapticConstant();
			// union의 setType()은 type 필드 자체를 안 채워서 여기서 직접 설정해야 한다
			// (안 그러면 SDL이 "Invalid haptic effect type: 0"으로 거부하는 걸 실측으로 확인)
			h.forceConstant.type = (short) SDL_HapticEffectType.SDL_HAPTIC_CONSTANT;
			h.forceConstant.direction = h.forceDirection;
			h.forceConstant.length = SdlHapticConst.SDL_HAPTIC_INFINITY;
		}
		h.forceConstant.level = (short) (sign * magnitude * 32767.0f);

		if (h.forceEffect == null) {
			h.forceEffect = new SDL_HapticEffect();
			h.forceEffect.constant = h.forceConstant;
			h.forceEffect.setType(SDL_HapticEffectType.SDL_HAPTIC_CONSTANT);
		}

		if (h.effectId < 0) {
			h.effectId = SdlHaptic.SDL_HapticNewEffect(h.haptic, h.forceEffect);
			if (h.effectId < 0) {
				captureError();
				return -1;
			}
		} else {
			if (SdlHaptic.SDL_HapticUpdateEffect(h.haptic, h.effectId, h.forceEffect) != 0) {
				captureError();
				return -1;
			}
		}

		// DirectInput이 포커스 변화 등으로 이펙트 재생을 조용히 멈출 수 있는데
		// UpdateEffect는 파라미터만 갱신할 뿐 재생 여부를 확인하지 않으므로
		// 재생 중이 아닐 때만 다시 Run 해서 스스로 복구되게 한다
		int status = SdlHaptic.SDL_HapticGetEffectStatus(h.haptic, h.effectId);
		if (status != 1) {
			if (SdlHaptic.SDL_HapticRunEffect(h.haptic, h.effectId, 1) != 0) {
				captureError();
				return -1;
			}
		}
		return 0;
	}

	// 장치가 스스로 타이밍을 렌더링하는 단발성 사인파 "킥"을 발생시킨다
	public static int mcrider_ffb_pulse(int handle, float magnitude, int periodMs, int durationMs, int startSign) {
		Handle h = handleAt(handle);
		if (h == null || h.haptic == null) return -1;

		magnitude = Math.max(0f, Math.min(1f, magnitude));
		if (periodMs < 10) periodMs = 10;
		if (durationMs < 1) durationMs = 1;
		if (startSign != 1 && startSign != -1) startSign = 1;

		if (h.pulseEffectId >= 0) {
			SdlHaptic.SDL_HapticDestroyEffect(h.haptic, h.pulseEffectId);
			h.pulseEffectId = -1;
		}

		// 네이티브 이펙트는 매번 새로 만들지만 (SDL에 단발 periodic 이펙트용 update가 없다)
		// Java 쪽 구조체 객체는 재사용한다
		if (h.pulseDirectionObj == null) {
			h.pulseDirectionObj = new SDL_HapticDirection();
			h.pulseDirectionObj.type = (byte) SDL_HapticDirectionEncoding.SDL_HAPTIC_CARTESIAN;
		}
		h.pulseDirectionObj.dir[0] = startSign;

		if (h.pulsePeriodicObj == null) {
			h.pulsePeriodicObj = new SDL_HapticPeriodic();
			// 여기도 type 필드를 직접 설정해야 한다 (mcrider_ffb_set_force와 동일한 이유)
			h.pulsePeriodicObj.type = (short) SDL_HapticEffectType.SDL_HAPTIC_SINE;
			h.pulsePeriodicObj.direction = h.pulseDirectionObj;
			h.pulsePeriodicObj.fadeLevel = 0;
		}
		h.pulsePeriodicObj.period = (short) periodMs;
		h.pulsePeriodicObj.magnitude = (short) (magnitude * 32767.0f);
		h.pulsePeriodicObj.length = durationMs;
		h.pulsePeriodicObj.fadeLength = (short) (durationMs / 2);

		if (h.pulseEffectObj == null) {
			h.pulseEffectObj = new SDL_HapticEffect();
			h.pulseEffectObj.periodic = h.pulsePeriodicObj;
			h.pulseEffectObj.setType(SDL_HapticEffectType.SDL_HAPTIC_SINE);
		}

		h.pulseEffectId = SdlHaptic.SDL_HapticNewEffect(h.haptic, h.pulseEffectObj);
		if (h.pulseEffectId < 0) {
			captureError();
			return -1;
		}

		if (SdlHaptic.SDL_HapticRunEffect(h.haptic, h.pulseEffectId, 1) != 0) {
			captureError();
			return -1;
		}
		return 0;
	}

	// 상태 조회와 무관하게 강제로 Stop 후 Run 한다 - 약 60~70초 후 힘 피드백이
	// 조용히 죽는 문제의 실제 해결책으로, UpdateEffect만으로는 리셋되지 않는
	// 펌웨어/드라이버 수준의 연속 출력 컷오프로 추정된다 (실측으로 확인)
	public static int mcrider_ffb_restart_effect(int handle) {
		Handle h = handleAt(handle);
		if (h == null || h.haptic == null || h.effectId < 0) return -1;

		SdlHaptic.SDL_HapticStopEffect(h.haptic, h.effectId);
		if (SdlHaptic.SDL_HapticRunEffect(h.haptic, h.effectId, 1) != 0) {
			captureError();
			return -1;
		}
		return 0;
	}

	public static int mcrider_ffb_stop(int handle) {
		Handle h = handleAt(handle);
		if (h == null || h.haptic == null) return -1;
		if (h.effectId >= 0) {
			SdlHaptic.SDL_HapticStopEffect(h.haptic, h.effectId);
		}
		return 0;
	}

	public static int mcrider_ffb_close(int handle) {
		if (handle < 0 || handle >= MAX_HANDLES) return -1;
		Handle h = handles[handle];
		if (h == null) return -1;
		if (h.haptic != null) {
			if (h.effectId >= 0) {
				SdlHaptic.SDL_HapticDestroyEffect(h.haptic, h.effectId);
			}
			if (h.pulseEffectId >= 0) {
				SdlHaptic.SDL_HapticDestroyEffect(h.haptic, h.pulseEffectId);
			}
			SdlHaptic.SDL_HapticClose(h.haptic);
		}
		if (h.joystick != null) {
			SdlJoystick.SDL_JoystickClose(h.joystick);
		}
		handles[handle] = null;
		return 0;
	}

	public static void mcrider_ffb_quit() {
		if (!initialized) return;
		for (int i = 0; i < MAX_HANDLES; i++) {
			if (handles[i] != null) {
				mcrider_ffb_close(i);
			}
		}
		Sdl.SDL_QuitSubSystem(SdlSubSystemConst.SDL_INIT_JOYSTICK | SdlSubSystemConst.SDL_INIT_HAPTIC);
		initialized = false;
	}

	private static Handle handleAt(int handle) {
		return handle >= 0 && handle < MAX_HANDLES ? handles[handle] : null;
	}
}
