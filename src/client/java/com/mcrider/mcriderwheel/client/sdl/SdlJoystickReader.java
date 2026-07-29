package com.mcrider.mcriderwheel.client.sdl;

import com.mcrider.mcriderwheel.client.WheelClientMain;
import io.github.libsdl4j.api.Sdl;
import io.github.libsdl4j.api.SdlSubSystemConst;
import io.github.libsdl4j.api.error.SdlError;
import io.github.libsdl4j.api.joystick.SDL_Joystick;
import io.github.libsdl4j.api.joystick.SDL_JoystickGUID;
import io.github.libsdl4j.api.joystick.SdlJoystick;
import io.github.libsdl4j.api.joystick.SdlJoystickConst;

/**
 * Reads device enumeration, button, axis, and hat state via SDL2
 * (SDL_Joystick) - this is now the sole source of joystick I/O for the
 * wheel subsystem, replacing GLFW entirely. Buttons moved here first, to
 * fix GLFW's Windows DirectInput backend misreporting buttons past index 32
 * on devices with large button counts - confirmed against this project's
 * own hardware via raw-HID-level logging that showed the device itself is
 * stable and the corruption happens in GLFW/DirectInput's parsing layer
 * (see PADDLE_SHIFTER_DEBUG_HANDOFF.md).
 * Axes/hats/enumeration were never observed to have that specific bug, but
 * were moved over anyway for one consistent input path instead of splitting
 * reads of the same physical device across two libraries.
 *
 * Axis values are normalized the same way GLFW's were (roughly -1.0..1.0,
 * from SDL's raw Sint16 range via /32767). WheelProfile.guid is now an SDL
 * GUID string rather than a GLFW one, and calibration is captured and
 * played back entirely through this class, so both are self-consistent -
 * but this is a clean break from any GLFW-calibrated profile saved before
 * this change; recalibration is expected, not a bug.
 */
public final class SdlJoystickReader {
	private static boolean sdlInitialized;
	// Set once an SDL_Init() call has failed and been logged, so a device
	// count/open() being retried every tick while it keeps failing (see
	// ensureSdlInit()) doesn't also spam the log/chat every tick - only the
	// first failure is reported, the retries themselves stay silent.
	private static boolean sdlInitFailureLogged;
	private static SDL_Joystick openJoystick;
	private static String openGuid;

	private SdlJoystickReader() {
	}

	/**
	 * Returns whether the joystick subsystem is actually initialized.
	 * Previously this latched sdlInitialized = true unconditionally after
	 * calling SDL_Init(), regardless of its return value - a failed init
	 * (missing/broken SDL2 native library, platform joystick backend
	 * unavailable, etc.) was never retried and every device query silently
	 * reported "0 devices" forever with no error logged anywhere. Not
	 * latching on failure lets deviceCount()/open() - both called every
	 * client tick - keep retrying, the same self-healing approach already
	 * used for a momentary per-device open() failure in WheelInput.tick().
	 */
	private static boolean ensureSdlInit() {
		if (sdlInitialized) return true;
		if (Sdl.SDL_Init(SdlSubSystemConst.SDL_INIT_JOYSTICK) != 0) {
			if (!sdlInitFailureLogged) {
				sdlInitFailureLogged = true;
				WheelClientMain.LOGGER.error("[Wheel] failed to initialize SDL joystick subsystem: {}", SdlError.SDL_GetError());
			}
			return false;
		}
		sdlInitialized = true;
		return true;
	}

	/**
	 * Opens the joystick whose SDL GUID string matches {@code guid}, unless
	 * a handle for that same GUID is already open. Closes any previously
	 * open (different) device first. Returns false if no matching device is
	 * currently present or the open call failed.
	 */
	public static boolean open(String guid) {
		if (guid == null || !ensureSdlInit()) return false;
		// Refreshed here (not just relying on the caller's own per-tick
		// update()) so SDL_JoystickGetAttached below reflects a disconnect
		// that happened since the last update() call, not stale state.
		SdlJoystick.SDL_JoystickUpdate();
		if (openJoystick != null && guid.equalsIgnoreCase(openGuid)) {
			// A same-GUID fast path alone can't tell "still the same live
			// device" apart from "this GUID was unplugged and replugged" -
			// SDL doesn't null out our handle on disconnect, so without this
			// check a replug would leave open() forever reusing a dead
			// handle instead of ever reopening the reconnected device.
			if (SdlJoystick.SDL_JoystickGetAttached(openJoystick)) return true;
		}
		close();

		int count = SdlJoystick.SDL_NumJoysticks();
		for (int i = 0; i < count; i++) {
			SDL_JoystickGUID deviceGuid = SdlJoystick.SDL_JoystickGetDeviceGUID(i);
			if (guid.equalsIgnoreCase(SdlJoystick.SDL_JoystickGetGUIDString(deviceGuid))) {
				openJoystick = SdlJoystick.SDL_JoystickOpen(i);
				if (openJoystick == null) return false;
				openGuid = guid;
				return true;
			}
		}
		return false;
	}

	public static void close() {
		if (openJoystick != null) {
			SdlJoystick.SDL_JoystickClose(openJoystick);
			openJoystick = null;
			openGuid = null;
		}
	}

	public static boolean isOpen() {
		return openJoystick != null;
	}

	/** GUID of the currently open device, or null if none is open. */
	public static String openGuid() {
		return openGuid;
	}

	/** Name of the currently open device, or "" if none is open. */
	public static String openName() {
		if (openJoystick == null) return "";
		String name = SdlJoystick.SDL_JoystickName(openJoystick);
		return name != null ? name : "";
	}

	/** Number of joystick devices SDL currently sees, present or not (not just the one this class has open). */
	public static int deviceCount() {
		if (!ensureSdlInit()) return 0;
		SdlJoystick.SDL_JoystickUpdate();
		return SdlJoystick.SDL_NumJoysticks();
	}

	/** Name of the device at enumeration index {@code index} (0..deviceCount()-1), without opening it. Empty if the index is no longer valid. */
	public static String deviceName(int index) {
		if (!isValidDeviceIndex(index)) return "";
		try {
			String name = SdlJoystick.SDL_JoystickNameForIndex(index);
			return name != null ? name : "";
		} catch (Throwable t) {
			return "";
		}
	}

	/**
	 * GUID string of the device at enumeration index {@code index}, without
	 * opening it, or null if that index isn't currently a real device.
	 *
	 * The index is re-validated against a live SDL_NumJoysticks() rather than
	 * trusted from whatever count the caller looped on: SDL re-enumerates
	 * whenever devices are opened/closed (which this mod does on wheel
	 * switches, calibration, and FFB reacquire), so an index that was valid
	 * when the loop started can be stale by the time it's read. Handing such an
	 * index to SDL_JoystickGetDeviceGUID yields a GUID struct whose memory
	 * SDL_JoystickGetGUIDString then walks off the end of - an
	 * EXCEPTION_ACCESS_VIOLATION that takes the whole JVM down with no
	 * catchable exception, confirmed from a crash in exactly this path.
	 */
	public static String deviceGuid(int index) {
		if (!isValidDeviceIndex(index)) return null;
		try {
			SDL_JoystickGUID guid = SdlJoystick.SDL_JoystickGetDeviceGUID(index);
			if (guid == null) return null;
			String s = SdlJoystick.SDL_JoystickGetGUIDString(guid);
			return s == null || s.isEmpty() ? null : s;
		} catch (Throwable t) {
			return null;
		}
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
		return openJoystick != null ? SdlJoystick.SDL_JoystickNumButtons(openJoystick) : 0;
	}

	public static boolean buttonDown(int index) {
		return openJoystick != null && SdlJoystick.SDL_JoystickGetButton(openJoystick, index) != 0;
	}

	public static int axisCount() {
		return openJoystick != null ? SdlJoystick.SDL_JoystickNumAxes(openJoystick) : 0;
	}

	/** Normalized to roughly -1.0..1.0 from SDL's raw Sint16 axis range - see this class's doc comment. */
	public static float axisValue(int index) {
		if (openJoystick == null) return 0f;
		short raw = SdlJoystick.SDL_JoystickGetAxis(openJoystick, index);
		return Math.max(-1f, raw / 32767f);
	}

	public static int hatCount() {
		return openJoystick != null ? SdlJoystick.SDL_JoystickNumHats(openJoystick) : 0;
	}

	/** Raw SDL_HAT_* bitmask (centered/up/right/down/left, or a diagonal OR of two) - numerically identical to GLFW's own hat convention. */
	public static byte hatValue(int index) {
		return openJoystick != null ? SdlJoystick.SDL_JoystickGetHat(openJoystick, index) : SdlJoystickConst.SDL_HAT_CENTERED;
	}

	public static boolean isHatCentered(byte value) {
		return value == SdlJoystickConst.SDL_HAT_CENTERED;
	}

	/**
	 * Must be called once per tick before reading button/axis state - unlike
	 * GLFW (whose joystick reads are always live), SDL's joystick state
	 * only refreshes on an explicit SDL_JoystickUpdate() call when the SDL
	 * event loop/SDL_PollEvent isn't being used, which it isn't here.
	 */
	public static void update() {
		if (sdlInitialized) SdlJoystick.SDL_JoystickUpdate();
	}
}
