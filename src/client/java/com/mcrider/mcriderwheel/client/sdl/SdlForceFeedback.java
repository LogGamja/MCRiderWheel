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
import io.github.libsdl4j.api.joystick.SDL_JoystickGUID;
import io.github.libsdl4j.api.joystick.SdlJoystick;

/**
 * Java port of what used to be native/src/mcrider_ffb.c, a hand-written C
 * shim called through a JNA interface (FfbNative/FfbNativeLoader) - this
 * class replaced that whole path with direct libsdl4j calls (ControllableSDL
 * was tried first and dropped - see build.gradle) - same SDL2 functions,
 * same effect setup, just no native/ build step (and no MinGW toolchain /
 * Windows-only build script) needed anymore. Confirmed working end-to-end on
 * real hardware; the old C file and its JNA interface have since been
 * deleted.
 *
 * This mirrors the deleted C file's function-for-function, handle-slot-array
 * design deliberately, not just its net effect: every comment below about
 * *why* a particular field is (or isn't) set reflects something that
 * version got right only after real-hardware testing - values were carried
 * over as-is rather than "cleaned up".
 *
 * WheelForceFeedback.java's crash-avoidance state machine (enable delay,
 * pulse cooldown, nativeFaulted latch, focus-regain reacquire) lives entirely
 * in that class and is unchanged by this port - this class only replaces
 * what used to be native calls.
 */
public final class SdlForceFeedback {
	private static final int MAX_HANDLES = 16;

	private static final class Handle {
		SDL_Joystick joystick;
		SDL_Haptic haptic;
		int effectId = -1;
		int pulseEffectId = -1;
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

	/** Bitmask from SDL_HapticQuery - which effect types + gain/autocenter support the device reports. 0 if the handle is invalid. */
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
		SdlJoystick.SDL_JoystickUpdate();
		return SdlJoystick.SDL_NumJoysticks();
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

	/** Null if the index isn't currently a real device - see SdlJoystickReader.deviceGuid() for why a stale index here is a JVM-killing access violation rather than a benign miss. */
	public static String mcrider_ffb_device_guid(int index) {
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
		if (index < 0 || !initialized) return false;
		try {
			return index < SdlJoystick.SDL_NumJoysticks();
		} catch (Throwable t) {
			return false;
		}
	}

	/** Opens the joystick at `index` for force feedback. Returns a handle (>= 0), or -1 if the device has no force feedback support. */
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

		SDL_Joystick joystick = SdlJoystick.SDL_JoystickOpen(index);
		if (joystick == null) {
			captureError();
			return -1;
		}

		SDL_Haptic haptic = SdlHaptic.SDL_HapticOpenFromJoystick(joystick);
		if (haptic == null) {
			captureError();
			SdlJoystick.SDL_JoystickClose(joystick);
			return -1;
		}

		// Disable the wheel's own on-device centering spring so it doesn't
		// fight the centering effect rendered from Java - one-shot setup,
		// not re-asserted per-tick (see mcrider_ffb_set_force). Best-effort:
		// ignore failure, not every device supports it.
		SdlHaptic.SDL_HapticSetAutocenter(haptic, 0);
		// Deliberately does NOT touch gain (SDL_HapticSetGain): it's a
		// device-global setting shared with the wheel's own vendor control
		// panel, and forcing it here was confirmed by testing to fight the
		// user's own setting without fixing anything.

		Handle h = new Handle();
		h.joystick = joystick;
		h.haptic = haptic;
		handles[slot] = h;
		return slot;
	}

	/**
	 * Sets/updates a continuously running constant-force effect.
	 * magnitude: 0.0 .. 1.0 (fraction of the device's max force)
	 * directionDeg: 0..359, SDL polar convention (0 = "north" / away from the player)
	 */
	public static int mcrider_ffb_set_force(int handle, float magnitude, float directionDeg) {
		Handle h = handleAt(handle);
		if (h == null || h.haptic == null) return -1;

		magnitude = Math.max(0f, Math.min(1f, magnitude));

		// Wheels report a single force-feedback axis, and DirectInput's
		// CreateEffect rejects SDL_HAPTIC_POLAR directions on single-axis
		// devices (SDL's polar->cartesian conversion produces more direction
		// components than the device has axes). SDL_HAPTIC_CARTESIAN with a
		// fixed unit direction and a signed level is the combination
		// confirmed working on single-axis wheels.
		int sign = (directionDeg >= 90.0f && directionDeg < 270.0f) ? -1 : 1;

		SDL_HapticDirection direction = new SDL_HapticDirection();
		direction.type = (byte) SDL_HapticDirectionEncoding.SDL_HAPTIC_CARTESIAN;
		direction.dir[0] = 1;

		SDL_HapticConstant constant = new SDL_HapticConstant();
		// The union's own setType() below only selects which member JNA
		// marshals - it does NOT set that member's own leading `type` field,
		// which is what actually lands at the shared union memory offset
		// SDL reads back to validate the effect. Leaving this unset (0)
		// made SDL_HapticNewEffect's JNA read-back see type=0 and throw
		// "Invalid haptic effect type: 0" - confirmed against real hardware.
		constant.type = (short) SDL_HapticEffectType.SDL_HAPTIC_CONSTANT;
		constant.direction = direction;
		constant.length = SdlHapticConst.SDL_HAPTIC_INFINITY;
		constant.level = (short) (sign * magnitude * 32767.0f);

		SDL_HapticEffect effect = new SDL_HapticEffect();
		effect.constant = constant;
		effect.setType(SDL_HapticEffectType.SDL_HAPTIC_CONSTANT);

		if (h.effectId < 0) {
			h.effectId = SdlHaptic.SDL_HapticNewEffect(h.haptic, effect);
			if (h.effectId < 0) {
				captureError();
				return -1;
			}
		} else {
			if (SdlHaptic.SDL_HapticUpdateEffect(h.haptic, h.effectId, effect) != 0) {
				captureError();
				return -1;
			}
		}

		// DirectInput can silently stop actually playing an effect (device
		// re-enumeration, exclusive-access hiccups, losing/regaining focus)
		// while SDL_HapticUpdateEffect above keeps reporting success anyway,
		// since it only rewrites the effect's parameters - it never confirms
		// playback is still live. Re-asserting playback here (only when the
		// device itself reports "not playing") is what makes that
		// self-healing without needlessly restarting a healthy effect at
		// the caller's tick rate. Devices without SDL_HAPTIC_STATUS return
		// a negative status - for those, fall back to restarting every call.
		int status = SdlHaptic.SDL_HapticGetEffectStatus(h.haptic, h.effectId);
		if (status != 1) {
			if (SdlHaptic.SDL_HapticRunEffect(h.haptic, h.effectId, 1) != 0) {
				captureError();
				return -1;
			}
		}
		return 0;
	}

	/**
	 * Fires a one-shot sine "kick" that the device/driver renders and times
	 * itself. magnitude: 0.0 .. 1.0. periodMs: length of one full sine
	 * cycle. durationMs: total pulse length (fades out over its back half).
	 * startSign: +1 or -1, see WheelForceFeedback.pulse()'s doc comment for
	 * why callers vary this.
	 */
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

		SDL_HapticDirection direction = new SDL_HapticDirection();
		direction.type = (byte) SDL_HapticDirectionEncoding.SDL_HAPTIC_CARTESIAN;
		direction.dir[0] = startSign;

		SDL_HapticPeriodic periodic = new SDL_HapticPeriodic();
		// See mcrider_ffb_set_force's comment - the member struct's own
		// `type` field has to be set explicitly, effect.setType() alone
		// isn't enough.
		periodic.type = (short) SDL_HapticEffectType.SDL_HAPTIC_SINE;
		periodic.direction = direction;
		periodic.period = (short) periodMs;
		periodic.magnitude = (short) (magnitude * 32767.0f);
		periodic.length = durationMs;
		periodic.fadeLength = (short) (durationMs / 2);
		periodic.fadeLevel = 0;

		SDL_HapticEffect effect = new SDL_HapticEffect();
		effect.periodic = periodic;
		effect.setType(SDL_HapticEffectType.SDL_HAPTIC_SINE);

		h.pulseEffectId = SdlHaptic.SDL_HapticNewEffect(h.haptic, effect);
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

	/** Live SDL_HapticGetEffectStatus for the centering effect - the -99/-98/-97 codes are this method's own "invalid handle/no haptic/no effect yet" cases, not SDL's. */
	public static int mcrider_ffb_effect_status(int handle) {
		Handle h = handleAt(handle);
		if (h == null) return -99;
		if (h.haptic == null) return -98;
		if (h.effectId < 0) return -97;
		return SdlHaptic.SDL_HapticGetEffectStatus(h.haptic, h.effectId);
	}

	/**
	 * Explicitly Stop()s then Run()s the centering effect already in place,
	 * regardless of what SDL_HapticGetEffectStatus claims - this is the
	 * actual fix for force feedback silently dying after roughly a minute
	 * (see WheelForceFeedback's own comment on this for the dedicated
	 * hardware testing that confirmed it); a firmware/driver-level
	 * max-continuous-output cutoff that in-place SDL_HapticUpdateEffect
	 * calls don't reset.
	 */
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
