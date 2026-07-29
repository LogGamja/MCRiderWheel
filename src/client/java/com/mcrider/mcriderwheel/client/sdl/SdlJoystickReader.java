package com.mcrider.mcriderwheel.client.sdl;

import com.mcrider.mcriderwheel.client.WheelClientMain;
import io.github.libsdl4j.api.Sdl;
import io.github.libsdl4j.api.SdlSubSystemConst;
import io.github.libsdl4j.api.error.SdlError;
import io.github.libsdl4j.api.joystick.SDL_Joystick;
import io.github.libsdl4j.api.joystick.SDL_JoystickGUID;
import io.github.libsdl4j.api.joystick.SdlJoystick;
import io.github.libsdl4j.api.joystick.SdlJoystickConst;

// GLFW 대신 SDL2로 조이스틱 열거/버튼/축/HAT을 읽는다.
// GLFW의 Windows DirectInput 백엔드가 32번 이상 버튼을 오독하는 문제를 피하기 위함.
// 축 값은 GLFW와 같은 방식으로 -1.0..1.0 정도로 정규화된다 (SDL Sint16 / 32767)
public final class SdlJoystickReader {
	private static boolean sdlInitialized;
	// SDL_Init() 실패를 한 번만 로그 남기기 위한 플래그
	private static boolean sdlInitFailureLogged;
	private static SDL_Joystick openJoystick;
	private static String openGuid;
	// deviceCount()/open()/update() 여러 곳에서 매 틱 몇 번씩 호출되므로
	// 실제 네이티브 폴은 몇 ms 단위로만 갱신되게 디바운스한다
	private static long lastNativeUpdateNanos = Long.MIN_VALUE / 2;
	private static final long UPDATE_DEBOUNCE_NANOS = 5_000_000L; // 5ms

	private static void pollIfStale() {
		if (!sdlInitialized) return;
		long now = System.nanoTime();
		if (now - lastNativeUpdateNanos >= UPDATE_DEBOUNCE_NANOS) {
			SdlJoystick.SDL_JoystickUpdate();
			lastNativeUpdateNanos = now;
		}
	}

	private SdlJoystickReader() {
	}

	// 초기화 실패해도 latch하지 않고 다음 틱에 재시도한다
	private static boolean ensureSdlInit() {
		if (sdlInitialized) return true;
		try {
			if (Sdl.SDL_Init(SdlSubSystemConst.SDL_INIT_JOYSTICK) != 0) {
				if (!sdlInitFailureLogged) {
					sdlInitFailureLogged = true;
					WheelClientMain.LOGGER.error("[Wheel] failed to initialize SDL joystick subsystem: {}", SdlError.SDL_GetError());
				}
				return false;
			}
		} catch (Throwable t) {
			if (!sdlInitFailureLogged) {
				sdlInitFailureLogged = true;
				WheelClientMain.LOGGER.error("[Wheel] failed to load/initialize SDL2", t);
			}
			return false;
		}
		sdlInitialized = true;
		return true;
	}

	// guid와 일치하는 조이스틱을 연다. 이미 같은 GUID가 열려 있으면 재사용한다
	public static boolean open(String guid) {
		if (guid == null || !ensureSdlInit()) return false;
		try {
			pollIfStale();
			if (openJoystick != null && guid.equalsIgnoreCase(openGuid)) {
				// SDL은 연결이 끊겨도 핸들을 null로 만들어주지 않으므로 attached 여부를 직접 확인한다
				if (SdlJoystick.SDL_JoystickGetAttached(openJoystick)) return true;
			}
			int index = -1;
			int count = SdlJoystick.SDL_NumJoysticks();
			for (int i = 0; i < count; i++) {
				if (guid.equalsIgnoreCase(deviceGuid(i))) {
					index = i;
					break;
				}
			}
			if (index < 0) return false;

			// 새 핸들을 먼저 열고 나서 옛것을 닫는다. SDL_JoystickOpen(index)에 유효하지
			// 않은 인덱스를 넘겨도 크래시 없이 null만 반환하는 걸 확인했으므로 문제 없다.
			// 반대로 열린 핸들에 GUID 재조회를 한 번 더 하면 JVM이 죽는 걸 확인해서
			// 여기서는 절대 그렇게 하지 않는다
			SDL_Joystick opened = SdlJoystick.SDL_JoystickOpen(index);
			if (opened == null) return false;
			close();
			openJoystick = opened;
			openGuid = guid;
			return true;
		} catch (Throwable t) {
			WheelClientMain.LOGGER.warn("[Wheel] open() failed for guid {}", guid, t);
			return false;
		}
	}

	public static void close() {
		if (openJoystick != null) {
			try {
				SdlJoystick.SDL_JoystickClose(openJoystick);
			} catch (Throwable t) {
				WheelClientMain.LOGGER.warn("[Wheel] SDL_JoystickClose failed", t);
			}
			openJoystick = null;
			openGuid = null;
		}
	}

	public static boolean isOpen() {
		return openJoystick != null;
	}

	public static String openGuid() {
		return openGuid;
	}

	public static String openName() {
		if (openJoystick == null) return "";
		try {
			String name = SdlJoystick.SDL_JoystickName(openJoystick);
			return name != null ? name : "";
		} catch (Throwable t) {
			return "";
		}
	}

	public static int deviceCount() {
		if (!ensureSdlInit()) return 0;
		pollIfStale();
		return SdlJoystick.SDL_NumJoysticks();
	}

	public static String deviceName(int index) {
		if (!isValidDeviceIndex(index)) return "";
		try {
			String name = SdlJoystick.SDL_JoystickNameForIndex(index);
			return name != null ? name : "";
		} catch (Throwable t) {
			return "";
		}
	}

	// index 번째 장치의 GUID 문자열, 유효하지 않으면 null.
	// 인덱스를 매번 실제 SDL_NumJoysticks()로 재검증한다 - 오래된 인덱스로
	// SDL_JoystickGetDeviceGUID를 부르면 JVM이 죽는 걸 실제 크래시로 확인했다.
	// 문자열 조립도 SDL_JoystickGetGUIDString 대신 formatJoystickGuid()로 직접 하는데
	// 그 네이티브 호출 자체가 다른 크래시 원인이었기 때문
	public static String deviceGuid(int index) {
		if (!isValidDeviceIndex(index)) return null;
		try {
			return formatJoystickGuid(SdlJoystick.SDL_JoystickGetDeviceGUID(index));
		} catch (Throwable t) {
			return null;
		}
	}

	private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();

	// SDL_JoystickGetGUIDString과 같은 32자 소문자 16진수 문자열을 순수 Java로 만든다.
	// libsdl4j가 SDL_GUID의 16바이트를 long 두 개로 매핑해두므로 네이티브 호출 없이 조립 가능하다
	static String formatJoystickGuid(SDL_JoystickGUID guid) {
		if (guid == null) return null;
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.nativeOrder());
		buf.putLong(guid.leastSigBits);
		buf.putLong(guid.mostSigBits);
		byte[] data = buf.array();

		StringBuilder sb = new StringBuilder(32);
		boolean allZero = true;
		for (byte b : data) {
			if (b != 0) allZero = false;
			sb.append(HEX_LOWER[(b >> 4) & 0xF]).append(HEX_LOWER[b & 0xF]);
		}
		// 전부 0이면 SDL이 말하는 "실제 장치 아님"이므로 null 처리
		return allZero ? null : sb.toString();
	}

	private static boolean isValidDeviceIndex(int index) {
		if (index < 0 || !sdlInitialized) return false;
		try {
			return index < SdlJoystick.SDL_NumJoysticks();
		} catch (Throwable t) {
			return false;
		}
	}

	public static int buttonCount() {
		if (openJoystick == null) return 0;
		try {
			return SdlJoystick.SDL_JoystickNumButtons(openJoystick);
		} catch (Throwable t) {
			return 0;
		}
	}

	public static boolean buttonDown(int index) {
		if (openJoystick == null) return false;
		try {
			return SdlJoystick.SDL_JoystickGetButton(openJoystick, index) != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	public static int axisCount() {
		if (openJoystick == null) return 0;
		try {
			return SdlJoystick.SDL_JoystickNumAxes(openJoystick);
		} catch (Throwable t) {
			return 0;
		}
	}

	public static float axisValue(int index) {
		if (openJoystick == null) return 0f;
		try {
			short raw = SdlJoystick.SDL_JoystickGetAxis(openJoystick, index);
			return Math.max(-1f, raw / 32767f);
		} catch (Throwable t) {
			return 0f;
		}
	}

	public static int hatCount() {
		if (openJoystick == null) return 0;
		try {
			return SdlJoystick.SDL_JoystickNumHats(openJoystick);
		} catch (Throwable t) {
			return 0;
		}
	}

	public static byte hatValue(int index) {
		if (openJoystick == null) return SdlJoystickConst.SDL_HAT_CENTERED;
		try {
			return SdlJoystick.SDL_JoystickGetHat(openJoystick, index);
		} catch (Throwable t) {
			return SdlJoystickConst.SDL_HAT_CENTERED;
		}
	}

	public static boolean isHatCentered(byte value) {
		return value == SdlJoystickConst.SDL_HAT_CENTERED;
	}

	// 매 틱 버튼/축을 읽기 전에 한 번 호출해야 한다. GLFW와 달리 SDL은
	// SDL_JoystickUpdate()를 명시적으로 부를 때만 상태가 갱신된다 (pollIfStale로 디바운스됨)
	public static void update() {
		pollIfStale();
	}
}
