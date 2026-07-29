package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.Minecraft;

/**
 * Runtime-normalized wheel state, computed from the raw SDL axes using
 * whatever {@link WheelProfile} was captured by {@link WheelCalibrationScreen}
 * for the currently connected device. Device enumeration and all input
 * values come from SDL (see SdlJoystickReader) - GLFW isn't used anywhere
 * in this class.
 */
public class WheelInput {
	public static boolean available;
	public static String activeDeviceName = "";
	public static WheelProfile activeProfile;
	public static String activeGuid;
	public static float steering; // -1 (left) .. 1 (right)
	public static float throttle; // 0 .. 1
	public static float brake;    // 0 .. 1
	// 0 = wheel within the configured steering lock, 1 = at (or past) the
	// wheel's actual physical end-stop. Only nonzero when the configured
	// lock is narrower than the physical range, i.e. there's room to
	// overturn past the "virtual" lock point.
	public static float steerOverTravel;

	public static void tick() {
		// SdlJoystickReader holds only one native handle open at a time - both
		// this method and WheelCalibrationScreen's own tick() call open() on it
		// every client tick, for whichever GUID each currently wants. With a
		// second (different) device being calibrated while this one is still
		// "the" driving device, those two ticks fight over the same handle and
		// re-open it for a different GUID 20x/sec, which has been observed to
		// hang the game outright (Windows' DirectInput joystick layer does not
		// appear to tolerate a handle being torn down and recreated that fast -
		// see WheelForceFeedback's own similar findings for its haptic handle).
		// Driving (WheelDrivingControl.tick()) and FFB output
		// (WheelForceFeedback.tick()) are both already fully suppressed while
		// the calibration screen is up, so freezing this class's state for that
		// same span - rather than fighting the wizard for the handle - costs
		// nothing real.
		if (Minecraft.getInstance().screen instanceof WheelCalibrationScreen) {
			return;
		}

		available = false;
		int count = SdlJoystickReader.deviceCount();

		// Calibrated *and driveable* devices currently connected, in the order
		// they should be tried: the player's explicitly preferred GUID (see
		// WheelConfig.getPreferredGuid()/WheelDeviceSelectScreen) first if it's
		// actually here, then everything else in SDL's own enumeration order -
		// same fallback as before preference existed, just giving the preferred
		// device first shot at it. isDriveable() keeps a profile whose steering
		// calibration failed (steerAxis = -1) from claiming the slot and
		// shutting out a second wheel that does work.
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

			// A momentary open() failure (e.g. this tick's enumeration index
			// raced a hotplug event) just skips this device for the tick;
			// the retry next tick is enough to self-heal.
			if (!SdlJoystickReader.open(guid)) continue;
			SdlJoystickReader.update();

			available = true;
			activeDeviceName = profile.name;
			activeProfile = profile;
			activeGuid = guid;
			steering = mapSteer(profile);
			steerOverTravel = computeOverTravel(profile);
			throttle = mapPedal(profile.throttleAxis, profile.throttleReleased, profile.throttlePressed);
			brake = mapPedal(profile.brakeAxis, profile.brakeReleased, profile.brakePressed);
			break;
		}

		if (!available) {
			// Deliberately doesn't call SdlJoystickReader.close() here - unlike
			// every other field below, that handle is a single shared static
			// resource WheelCalibrationScreen may also have open (e.g. for a
			// not-yet-calibrated device, which never makes it into this
			// method's "available" branch at all). Closing it here on every
			// tick this loop finds no *calibrated* device would rip that
			// handle out from under an in-progress calibration.
			//
			// The loop above found no calibrated device this tick - clear the
			// "active" fields along with it rather than leaving them pointing
			// at whatever was last connected, since callers like
			// WheelSettingsScreen's sliders read/write activeProfile directly
			// without going through available.
			activeDeviceName = "";
			activeProfile = null;
			activeGuid = null;
			steering = 0f;
			throttle = 0f;
			brake = 0f;
			steerOverTravel = 0f;
		}
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

	/**
	 * Which captured lock (steerRight vs steerLeft) the raw reading {@code v}
	 * is actually closer to. Not "v >= steerCenter": that assumes steerRight
	 * always sits on the raw-increasing side of center, which is only true
	 * for a "normal" axis polarity. On a reversed axis (turning right
	 * decreases the raw value), steerRight is captured *below* center, so
	 * "v >= c" would misroute a genuine right turn into the left-lock's span
	 * - same sign by coincidence (both span and diff flip together), but the
	 * wrong span's magnitude, which is wrong whenever the two locks aren't
	 * symmetric. Comparing raw distance to each captured extreme instead is
	 * polarity-agnostic.
	 */
	private static boolean isTowardRight(float v, WheelProfile p) {
		return Math.abs(v - p.steerRight) <= Math.abs(v - p.steerLeft);
	}

	// The soft-lock "wall" reaches full strength this many degrees past the
	// configured lock, not spread across whatever's left of the physical
	// range - a 90 degree lock on a 900 degree wheel should feel like a
	// hard stop right at 90, not a force that only maxes out near 900.
	private static final float WALL_WIDTH_DEG = 8f;
	private static final float DEFAULT_WALL_WIDTH_RAW = 0.05f; // fallback when steerRawPerDegree is unknown

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
		if (overRange < 1e-4f) return 0f; // configured lock already is the physical limit - no room to overturn

		float wallWidth = p.steerRawPerDegree > 1e-6f ? WALL_WIDTH_DEG * p.steerRawPerDegree : DEFAULT_WALL_WIDTH_RAW;
		wallWidth = Math.min(wallWidth, overRange);
		return clamp((travel - lockRaw) / wallWidth, 0f, 1f);
	}

	private static float mapPedal(int axis, float released, float pressed) {
		if (axis < 0 || axis >= SdlJoystickReader.axisCount()) return 0f;
		float v = SdlJoystickReader.axisValue(axis);
		float span = pressed - released;
		return Math.abs(span) < 1e-4f ? 0f : clamp((v - released) / span, 0f, 1f);
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
