package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Uses SDL's raw joystick API (not GLFW, not a gamepad-mapping API) so that
 * non-Xbox-layout devices like a Thrustmaster T500 RS wheel are read with
 * their real axis/button layout instead of being forced into a generic
 * controller mapping - see SdlJoystickReader for why SDL specifically.
 *
 * Driving control and force feedback both arm themselves automatically
 * whenever a calibrated wheel is present - there is no on/off key for
 * either anymore. Calibration lives in the ESC menu's "MCRider Wheel"
 * screen. The zombie-attacks-door sound used as an impact-pulse cue is
 * hooked at the packet level (see SoundPacketMixin), not via
 * SoundManager.addListener() - that listener only fires after the local
 * audio engine has already computed a non-zero playback volume, so a
 * sound that's server-broadcast but too quiet/far to actually be heard
 * locally never reaches it.
 */
public class WheelClientMain implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcriderwheel");

	// Keyed by GUID rather than SDL's enumeration index - unlike GLFW's
	// jid (stable per physical slot while connected), SDL's device index
	// isn't a stable identity and can shift when other devices connect or
	// disconnect, which would misattribute connect/disconnect events under
	// an index-keyed map.
	private final Set<String> connectedGuids = new HashSet<>();
	// Set when a present joystick has no saved WheelConfig profile (checked
	// on world join, and again whenever a joystick is freshly connected
	// mid-session) - consumed on the next tick where a world is actually
	// loaded and no other screen is up, rather than forcing the screen open
	// immediately, since forcing it during the join sequence itself risks
	// getting clobbered by the game's own loading-screen transitions.
	private boolean pendingAutoCalibration;

	@Override
	public void onInitializeClient() {
		WheelForceFeedback.init();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			WheelForceFeedback.shutdown();
			WheelDrivingControl.shutdown(client);
			// WheelForceFeedback.shutdown() only tears down its own haptic
			// handles/subsystem - the separate SDL_Joystick handle WheelInput/
			// WheelCalibrationScreen keep open through SdlJoystickReader for
			// buttons/axes is otherwise left open (and the joystick subsystem
			// left refcounted up) until the process exits.
			SdlJoystickReader.close();
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (!anyCalibratedJoystickPresent() && anyUncalibratedJoystickPresent()) {
				pendingAutoCalibration = true;
			}
		});

		WorldRenderEvents.START.register(context ->
				safeTick("tickRotation", () -> WheelDrivingControl.tickRotation(Minecraft.getInstance())));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Each subsystem runs in its own try/catch rather than one around the
			// whole tick: TireGripFeedback/VehicleDirectionDebug in particular
			// read MCRider's internal state through ad-hoc, reverse-engineered
			// heuristics (attribute-modifier IDs, a passenger's display name -
			// see their own doc comments) that an MCRider update could change
			// out from under this mod at any time. A Java exception there
			// shouldn't be able to take driving/FFB down with it for the rest
			// of the tick, let alone crash the whole client - it's isolated,
			// logged, and simply retried next tick instead.
			safeTick("checkConnections", () -> checkConnections(client));
			safeTick("WheelInput", WheelInput::tick);
			safeTick("WheelDrivingControl", () -> WheelDrivingControl.tick(client));
			// Both feed WheelForceFeedback's per-tick state (extra resistance /
			// grip vibration) - must run before WheelForceFeedback.tick() itself
			// or FFB always renders one tick stale.
			safeTick("VehicleDirectionDebug", () -> VehicleDirectionDebug.tick(client));
			safeTick("TireGripFeedback", () -> TireGripFeedback.tick(client));
			safeTick("WheelForceFeedback", WheelForceFeedback::tick);

			if (pendingAutoCalibration && client.player != null && client.screen == null) {
				pendingAutoCalibration = false;
				safeTick("open calibration screen", () -> client.setScreen(new WheelCalibrationScreen()));
			}
		});
	}

	/**
	 * Runs one subsystem's per-tick/per-frame work in isolation. A JNA/SDL
	 * call crashing the JVM outright (EXCEPTION_ACCESS_VIOLATION) can't be
	 * caught by any Java try/catch - that class of failure is guarded against
	 * at its own source instead (see SdlJoystickReader/SdlForceFeedback's own
	 * try/catches and WheelForceFeedback's nativeFaulted latch). This is the
	 * separate case of an ordinary Java exception (NPE, out-of-bounds,
	 * a third-party mod's data shape changing) - those don't corrupt JVM
	 * state, so unlike a native fault there's no reason to stop retrying;
	 * logging and letting the next tick try again is enough.
	 */
	private static void safeTick(String name, Runnable task) {
		try {
			task.run();
		} catch (Throwable t) {
			LOGGER.error("[MCRiderWheel] {} tick failed - continuing", name, t);
		}
	}

	/**
	 * "Uncalibrated" here means "can't actually steer yet", not just "has no
	 * saved profile" - a device that only went through BUTTONS_ONLY (e.g. a
	 * paddle-shifter box, or a wheel whose steering calibration was skipped)
	 * saves a profile with steerAxis = -1, which WheelInput's own
	 * isDriveable() check already treats as non-driving. Without also
	 * checking it here, that saved-but-undriveable profile would count as
	 * "already calibrated" and the auto-calibration prompt would never offer
	 * to finish wiring up steering for it.
	 */
	private boolean anyUncalibratedJoystickPresent() {
		int count = SdlJoystickReader.deviceCount();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null || WheelConfig.isAutoCalibrationDeclined(guid)) continue;
			WheelProfile profile = WheelConfig.get(guid);
			if (profile == null || !profile.isDriveable()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether some present joystick already has a saved, driveable profile -
	 * if so, the player has a working calibrated wheel and an unrelated
	 * uncalibrated device (e.g. a gamepad, or another wheel that only ever
	 * got BUTTONS_ONLY treatment) shouldn't force the calibration wizard open.
	 */
	private boolean anyCalibratedJoystickPresent() {
		int count = SdlJoystickReader.deviceCount();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null) continue;
			WheelProfile profile = WheelConfig.get(guid);
			if (profile != null && profile.isDriveable()) {
				return true;
			}
		}
		return false;
	}

	private void say(Minecraft client, String msg) {
		LOGGER.info(msg);
		if (client.gui != null) {
			client.gui.getChat().addMessage(Component.literal(msg));
		}
	}

	private void checkConnections(Minecraft client) {
		Set<String> present = new HashSet<>();
		int count = SdlJoystickReader.deviceCount();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null) continue;
			present.add(guid);
			if (!connectedGuids.contains(guid)) {
				String name = SdlJoystickReader.deviceName(i);
				say(client, "[MCRiderWheel] joystick connected: " + name + " (guid=" + guid + ")");

				WheelProfile profile = WheelConfig.get(guid);
				if ((profile == null || !profile.isDriveable()) && !anyCalibratedJoystickPresent()
						&& !WheelConfig.isAutoCalibrationDeclined(guid)) {
					pendingAutoCalibration = true;
				}
			}
		}
		for (String guid : connectedGuids) {
			if (!present.contains(guid)) {
				say(client, "[MCRiderWheel] joystick disconnected (guid=" + guid + ")");
			}
		}
		connectedGuids.clear();
		connectedGuids.addAll(present);
	}
}
