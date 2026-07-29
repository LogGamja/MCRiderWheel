package com.mcrider.mcriderwheel.client.ffb;

import com.mcrider.mcriderwheel.client.WheelCalibrationScreen;
import com.mcrider.mcriderwheel.client.WheelInput;
import com.mcrider.mcriderwheel.client.WheelProfile;
import com.mcrider.mcriderwheel.client.WheelClientMain;
import com.mcrider.mcriderwheel.client.sdl.SdlForceFeedback;
import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.Minecraft;

/**
 * Drives a simple self-centering force back into the wheel: the further
 * the wheel is turned from center, the harder it pushes back - just
 * enough to prove the FFB path actually reaches the hardware. Real
 * per-corner/curb/collision forces belong in MCRider itself later; this
 * is only the recognition/plumbing test.
 */
public final class WheelForceFeedback {
	// Centering force strength while still within the configured lock. Must
	// stay below 1 so the soft-lock wall (which ramps up to full strength
	// past the lock) actually has headroom to feel like a distinct jump -
	// at STRENGTH=1 the centering force already saturates to max right at
	// the lock boundary, leaving nothing for the wall to add.
	private static final float STRENGTH = 0.5f;
	// Extra centering-force boost applied while the wheel's movement
	// heading and MCRider's direction-indicator entity disagree by more
	// than the mismatch threshold (see VehicleDirectionDebug) - makes the
	// wheel noticeably heavier/stiffer as a "something's off" cue.
	private static final float EXTRA_RESISTANCE_BOOST = 0.2f;

	private static final int PULSE_PERIOD_MS = 40; // ~25 Hz wobble, rendered by the device itself

	private static boolean sdlAvailable;
	private static boolean enabled;
	private static int handle = -1;
	private static boolean forceLoggedOnce = true;
	// Windows DirectInput (what SDL2's haptic backend uses under the hood)
	// silently un-acquires the device when the game window loses focus, so
	// force output just stops with no error - alt-tabbing away and back
	// needs the device closed and reopened to get DirectInput to reacquire
	// it and start actually outputting force again.
	private static boolean wasWindowActive = true;
	// GUID of the last profile enable() failed to open, so tick() doesn't
	// retry (and re-log the full SDL device dump) 20x/sec for a wheel with
	// no FFB support or an unmatched GUID - cleared on disconnect so a
	// later reconnect still gets a fresh attempt.
	private static String failedGuid;
	// GUID the currently-open handle actually belongs to. Without this, enable()
	// would latch onto whichever device was active when it first ran and never
	// re-check: switching wheels (WheelDeviceSelectScreen, or recalibrating a
	// second device) moves WheelInput to the new one while force output kept
	// going to the old one - steering one wheel, feeling the other.
	private static String enabledGuid;
	private static volatile boolean extraResistance;
	// Set from TireGripFeedback: 0 = no grip-loss vibration, otherwise its
	// strength (0..1). Deliberately rendered as an oscillating direction on
	// the same continuous constant-force channel as centering, rather than
	// via pulse() - pulse() destroys and recreates a one-shot effect on
	// every call, which is fine for an occasional kick but re-triggering it
	// every tick just destroys each burst before the device ever renders
	// it, so nothing ends up felt (see PULSE_COOLDOWN_NANOS's crash-history
	// comment for why pulse() is touchy about rapid re-calls in general).
	private static volatile float gripVibrationMagnitude;
	private static int vibrationPhaseTicks;
	private static final int VIBRATION_HALF_PERIOD_TICKS = 1; // ~10Hz square-wave buzz at the 20Hz client tick rate
	// Guards against hammering mcrider_ffb_pulse() - e.g. several zombies
	// breaking doors near each other can fire many sound packets within a
	// few ms on a busy server, and calling the native pulse function that
	// rapidly has been observed to crash the JVM with a JNA "Invalid memory
	// access" (native-side effect create/destroy race, most likely).
	private static final long PULSE_COOLDOWN_NANOS = 150_000_000L; // matches the 150ms pulse duration used everywhere
	private static long lastPulseNanos;
	// Separate from lastPulseNanos itself since System.nanoTime() isn't
	// guaranteed to be non-negative or zero at startup - a plain "has this
	// ever fired" sentinel value could theoretically collide with a real
	// timestamp, where `now - lastPulseNanos` wrapping around long overflow
	// would gate every future pulse call forever.
	private static boolean everPulsed;
	// If a native call ever does crash despite the above, don't keep
	// calling back into what's now a possibly-corrupted native library -
	// disable FFB for the rest of the session instead of risking another
	// (uncatchable-in-practice) crash to desktop.
	private static volatile boolean nativeFaulted;
	// SdlJoystickReader already holds the wheel open for buttons/axes;
	// enable() then has SDL open the same physical device a second time
	// (a separate handle) for haptics. Doing that the instant the device
	// is first seen
	// (e.g. right as a world loads and a previously-calibrated wheel is
	// detected) has been observed to hard-crash the process with a raw
	// Windows access violation (0xC0000005) that never even produces a
	// crash report - not a JNA-catchable exception, so try/catch around
	// the native calls can't help here. Waiting for the device to sit
	// "available" for a bit before asking SDL to open it is a mitigation
	// for what looks like a driver-side reentrancy issue, not a real fix.
	private static final long ENABLE_ARM_DELAY_NANOS = 1_000_000_000L;
	// -1 means "not currently available" (mirrors availableSinceNanos reset on disconnect).
	private static long availableSinceNanos = -1;
	// GUID availableSinceNanos is currently timing, so switching devices
	// restarts that window instead of inheriting the old device's elapsed time.
	private static String armedGuid;

	private WheelForceFeedback() {
	}

	public static void init() {
		// Unlike the old native/mcrider_ffb.c + JNA path (a separate .dll
		// that could fail to load), this touches libsdl4j's own bundled SDL2
		// native library (see build.gradle - ControllableSDL was tried first
		// and dropped) - JNA loads it lazily on first use, so
		// this call both forces that load attempt now (rather than
		// surprising enable() with it later) and doubles as SdlForceFeedback's
		// own SDL_InitSubSystem(JOYSTICK|HAPTIC) call.
		try {
			sdlAvailable = SdlForceFeedback.mcrider_ffb_init() == 0;
		} catch (Throwable t) {
			WheelClientMain.LOGGER.error("[FFB] failed to load/initialize SDL force feedback", t);
			sdlAvailable = false;
		}
	}

	public static boolean isNativeAvailable() {
		return sdlAvailable;
	}

	/** Set from VehicleDirectionDebug: whether the movement/direction-entity yaw mismatch currently exceeds its threshold. */
	public static void setExtraResistance(boolean active) {
		extraResistance = active;
	}

	/** Set from TireGripFeedback: grip-loss vibration strength (0..1), 0 = none. */
	public static void setGripVibrationMagnitude(float magnitude) {
		gripVibrationMagnitude = Math.max(0f, Math.min(1f, magnitude));
	}

	private static void enable() {
		WheelProfile profile = WheelInput.activeProfile;
		if (profile == null) return;

		try {
			SdlForceFeedback.mcrider_ffb_init();
			int index = findSdlIndexForGuid(profile.guid);
			if (index < 0) {
				index = findSdlIndexForName(profile.name);
			}
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

	/**
	 * Deliberately doesn't route its own native-call failures through
	 * {@link #onNativeFault} - that method calls back into disable(), and
	 * a crash from disable()'s own stop/close calls would recurse into
	 * itself forever instead of just disabling FFB once.
	 */
	private static void disable(String reason) {
		if (enabled) {
			WheelClientMain.LOGGER.info("[FFB] disabling force feedback ({})", reason);
		}
		enabled = false;
		enabledGuid = null;
		failedGuid = null;
		// Re-arm the settle delay so whatever reopens next waits it out again.
		// Without this, a device that opens fine but whose set_force then
		// keeps failing would close+reopen every single tick (the failure
		// path calls disable(), and availableSinceNanos was set back when the
		// wheel first appeared, so "settled" is already true) - a 20 Hz
		// DirectInput open/close storm, which is the same shape of abuse that
		// hard-crashed the process before ENABLE_ARM_DELAY_NANOS existed.
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

	/**
	 * SdlJoystickReader/SdlForceFeedback each enumerate joysticks
	 * independently, so we match devices by their GUID string rather than
	 * assuming matching indices.
	 */
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

	private static int findSdlIndexForName(String name) {
		if (name == null || !sdlAvailable) return -1;
		int count = SdlForceFeedback.mcrider_ffb_device_count();
		for (int i = 0; i < count; i++) {
			if (name.equalsIgnoreCase(SdlForceFeedback.mcrider_ffb_device_name(i))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Fires a one-shot "kick" - a stand-in for landing/collision impacts
	 * until those are wired up to real game events. This runs as its own
	 * hardware-timed SDL_HAPTIC_SINE effect (see SdlForceFeedback.mcrider_ffb_pulse)
	 * rather than being faked by repeatedly nudging the continuous centering
	 * force from the ~20 Hz client tick: at that poll rate a "3 wobbles
	 * in 150ms" wave aliases almost perfectly with the sampling interval
	 * and collapses into what's effectively a single fixed-sign nudge -
	 * which is why the very first attempt at this only ever pulled one
	 * direction no matter which way the wheel was turned.
	 *
	 * The kick's first half-cycle leads toward the same side the wheel is
	 * currently turned - right turn kicks right, left turn kicks left.
	 * Recall sign=+1 (dir[0]=+1) is physically "push left" (it's what
	 * mcrider_ffb_set_force sends for direction_deg=0, which centers a
	 * right turn), so matching the turn direction means the *opposite*
	 * sign of what centering would use for that same turn.
	 */
	public static void pulse(float magnitude, long durationMs) {
		if (nativeFaulted || !enabled || !sdlAvailable || handle < 0) {
			return;
		}
		// Same reasoning as tick()'s own calibration-screen guard below -
		// don't fight the player's hand with an impact kick while they're
		// deliberately holding the wheel steady at a lock/reference point.
		// This wasn't covered by that guard since pulse() is called
		// directly from SoundPacketMixin, not from tick().
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

	/**
	 * Closes the haptic handle ahead of a deliberate device switch. Both this
	 * class and SdlJoystickReader hold their own SDL_JoystickOpen on the same
	 * physical device (this one additionally wraps it in
	 * SDL_HapticOpenFromJoystick), and the reader repoints its handle inside
	 * WheelInput.tick() - which runs *before* this class's own tick() notices
	 * the GUID changed. Calling this from the code that initiates the switch
	 * keeps the haptic side from briefly outliving the joystick handle it was
	 * opened from. tick()'s enabledGuid check still covers switches that
	 * happen without going through here (hotplug, recalibration).
	 */
	public static void releaseForDeviceChange() {
		if (enabled) disable("device change requested");
	}

	private static void onNativeFault(String call, Throwable t) {
		nativeFaulted = true;
		WheelClientMain.LOGGER.error("[FFB] native call '{}' crashed, disabling force feedback for the rest of this session", call, t);
		disable("native fault in " + call);
	}

	// Players were previously having to set the FFB-strength slider to
	// ~150% just to feel the effect at all - this boosts every setting's
	// actual output by that same factor, so e.g. 100% now delivers what
	// 150% used to.
	private static final float STRENGTH_BOOST = 1.5f;

	private static float strengthMultiplier() {
		WheelProfile profile = WheelInput.activeProfile;
		float percent = profile != null ? profile.ffbStrengthPercent : 100f;
		return percent / 100f * STRENGTH_BOOST;
	}

	/**
	 * Force feedback has no on/off switch anymore - it opens automatically
	 * whenever a calibrated wheel is present, and the FFB-strength slider
	 * (0% = no output) is what used to be the toggle.
	 */
	public static void tick() {
		if (!sdlAvailable || nativeFaulted) return;

		boolean windowActive = Minecraft.getInstance().isWindowActive();
		if (windowActive && !wasWindowActive && enabled) {
			// A full close+reopen is a slow, blocking DirectInput round trip. A
			// window whose focus flickers every few seconds (observed in this
			// project's own logs) would otherwise trigger one every flicker,
			// which reads as a hard stutter each time. Try the cheap in-place
			// restart first and only fall back to the expensive reacquire if
			// that actually fails - a genuine un-acquire makes it fail, while a
			// spurious focus blip (where the device was never really lost)
			// succeeds and costs nothing.
			if (handle >= 0 && SdlForceFeedback.mcrider_ffb_restart_effect(handle) != 0) {
				disable("window focus regained, reopening so DirectInput reacquires");
			}
		}
		wasWindowActive = windowActive;

		WheelProfile profile = WheelInput.activeProfile;
		// The driving device changed out from under an already-open handle
		// (wheel switched in WheelDeviceSelectScreen, a second device
		// calibrated, preferred GUID changed) - close so the block below
		// reopens on whatever WheelInput is actually reading now.
		if (enabled && enabledGuid != null && !enabledGuid.equals(WheelInput.activeGuid)) {
			disable("active wheel changed to " + WheelInput.activeGuid);
		}
		boolean alreadyFailed = profile != null && profile.guid != null && profile.guid.equals(failedGuid);
		if (WheelInput.available) {
			// Restart the settle window whenever the target device changes, not
			// just on disable(): opening a device for haptics while SDL is still
			// re-enumerating from the *previous* device's open/close is exactly
			// the reentrancy this delay exists to avoid, and a switch churns the
			// device list just as much as a fresh connect does.
			if (availableSinceNanos < 0 || !java.util.Objects.equals(armedGuid, WheelInput.activeGuid)) {
				availableSinceNanos = System.nanoTime();
				armedGuid = WheelInput.activeGuid;
			}
			// SdlJoystickReader having actually settled on this same device is
			// the signal that its own open/close churn is done - opening a
			// second (haptic) handle before then is what the arm delay is
			// really guarding against, so check it rather than only the clock.
			boolean readerSettled = WheelInput.activeGuid != null
					&& WheelInput.activeGuid.equalsIgnoreCase(SdlJoystickReader.openGuid());
			boolean settled = System.nanoTime() - availableSinceNanos >= ENABLE_ARM_DELAY_NANOS;
			// WheelInput.tick() freezes steering/available/activeGuid while the
			// calibration screen is up rather than fighting it for the SDL
			// joystick handle (see that class's own comment on this) - but that
			// freeze can leave WheelInput.available sitting true from just before
			// the screen opened. Without this check, re-calibrating the
			// already-active wheel would open a second (haptic) handle onto a
			// device the wizard is mid-recalibration on, then immediately stop it
			// again a few lines below - opening for nothing and fighting the
			// wizard for the reader's handle in between.
			boolean calibrating = Minecraft.getInstance().screen instanceof WheelCalibrationScreen;
			if (settled && readerSettled && !calibrating && !enabled && !alreadyFailed) {
				enable();
			}
		} else {
			// disable() only runs (and clears failedGuid) when enabled was
			// true; a device whose enable() itself failed leaves enabled
			// false forever, so failedGuid needs clearing here too - on
			// disconnect, regardless of whether we ever got as far as
			// "enabled" - or a later reconnect of the same GUID would never
			// get a fresh attempt for the rest of the session.
			failedGuid = null;
			if (enabled) disable("wheel no longer available");
			// After disable(), so the settle delay is measured from the
			// reconnect rather than from this disconnect.
			availableSinceNanos = -1;
		}

		if (!enabled || handle < 0) return;

		// Don't fight the player's hand while they're deliberately holding
		// the wheel at a lock/reference point during calibration. Checked
		// fresh from the live screen each tick (rather than a manually
		// toggled suspend/resume flag) so there's no way for output to get
		// stuck off if the calibration screen ever closes through a path
		// that skips its normal onClose().
		if (Minecraft.getInstance().screen instanceof WheelCalibrationScreen) {
			try {
				SdlForceFeedback.mcrider_ffb_stop(handle);
			} catch (Throwable t) {
				onNativeFault("stop", t);
			}
			return;
		}

		float magnitude;
		float direction;
		if (gripVibrationMagnitude > 0f) {
			// Tires losing grip take over the channel entirely instead of
			// blending with centering - fighting a real slide with a
			// centering pull at the same time would just read as extra
			// resistance, not a slide.
			magnitude = Math.min(1f, gripVibrationMagnitude * strengthMultiplier());
			vibrationPhaseTicks++;
			boolean phaseHigh = (vibrationPhaseTicks / VIBRATION_HALF_PERIOD_TICKS) % 2 == 0;
			direction = phaseHigh ? 0f : 180f;
		} else {
			float steering = WheelInput.steering; // -1 (left) .. 1 (right)
			magnitude = Math.min(1f, Math.abs(steering) * STRENGTH);
			// Soft lock: once the wheel is turned past the configured steering
			// lock (only possible if that lock is narrower than the wheel's
			// real physical range), ramp the centering force up to full
			// strength so the end-stop feels like a hard physical wall.
			float overTravel = WheelInput.steerOverTravel;
			if (overTravel > 0f) {
				magnitude = magnitude + (1f - magnitude) * overTravel;
			}
			if (extraResistance) {
				magnitude = Math.min(1f, magnitude + EXTRA_RESISTANCE_BOOST);
			}
			magnitude = Math.min(1f, magnitude * strengthMultiplier());
			// Push opposite to the turn direction so the wheel self-centers
			// instead of reinforcing the turn - measured against the real
			// device, this is the correct sign (do not "simplify" back to
			// matching steering's sign without retesting on hardware).
			direction = steering >= 0 ? 0f : 180f;
		}
		try {
			int result = SdlForceFeedback.mcrider_ffb_set_force(handle, magnitude, direction);

			if (result == 0) {
				// Prefer restarting right as the wheel passes back through
				// center - magnitude is ~0 there, so the restart's brief
				// stop+run blip is effectively unfelt, unlike restarting on a
				// blind timer while the player is actively holding a turn
				// (which was felt as a slight catch every 15s). Edge-triggered
				// (only on the tick the wheel *enters* the center zone, not
				// every tick spent parked there) so it can't fire dozens of
				// times a second while idling at center.
				boolean nearCenter = Math.abs(WheelInput.steering) <= CENTER_RESET_DEADZONE;
				restartTicks++;
				if ((nearCenter && !wasNearCenter) || restartTicks >= RESTART_FALLBACK_TICKS) {
					restartTicks = 0;
					periodicRestart();
				}
				wasNearCenter = nearCenter;
			}

			if (result != 0) {
				// DirectInput can silently drop/un-acquire the device outside
				// of the alt-tab case this class already handles (e.g. a
				// focus blip shorter than one client tick, which our own
				// windowActive polling never observes as a transition) -
				// once that happens every future set_force call just keeps
				// failing with enabled/handle otherwise looking fine, and
				// forceLoggedOnce used to silence all but the very first
				// such failure, so this could die completely silently.
				// Reacting here - logging every time, and forcing the same
				// close+reopen that alt-tab-recovery already relies on -
				// means a fresh reconnect attempt happens automatically
				// instead of requiring the player to alt-tab to notice.
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

	// This is the actual fix for force feedback silently dying after roughly
	// a minute: confirmed by testing (a standalone harness with zero
	// Minecraft/JVM/GLFW involvement) that a single
	// long-lived effect instance, only ever adjusted in place via
	// SDL_HapticUpdateEffect, dies on its own after ~60-70s regardless of
	// caller - almost certainly a firmware/driver-level max-continuous-
	// output safety cutoff that in-place parameter updates don't reset,
	// since it's still "the same effect" from the device's point of view.
	// A genuine Stop()+Run() on that same effect, well under that window,
	// was confirmed by testing to prevent it indefinitely with no device
	// close/reopen - much cheaper than the full joystick+haptic reacquire
	// below, which is reserved for focus-regain and outright set_force
	// failures.
	//
	// Restarting on a blind timer while the wheel was actively held in a
	// turn was still felt as a slight catch every interval (magnitude isn't
	// near 0 then), so this instead prefers restarting the moment the wheel
	// passes back through center - see the nearCenter check above. The
	// fixed-interval restart only exists as a fallback for the edge case of
	// the wheel being held off-center continuously with no center crossing
	// to piggyback on; kept comfortably under the ~60-70s failure window.
	private static final float CENTER_RESET_DEADZONE = 0.05f; // |steering| below this counts as "at center" - magnitude here is negligible, so the restart blip isn't felt
	private static final int RESTART_FALLBACK_TICKS = 600; // 30s at the 20Hz client tick
	private static int restartTicks;
	private static boolean wasNearCenter = true;

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
