package com.mcrider.wheeltest.client;

import com.mcrider.wheeltest.client.ffb.WheelForceFeedback;
import com.mcrider.wheeltest.client.sdl.SdlJoystickReader;
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
public class WheelTestClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcrider-wheel-test");

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

		WorldRenderEvents.START.register(context -> WheelDrivingControl.tickRotation(Minecraft.getInstance()));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			checkConnections(client);
			WheelInput.tick();
			WheelDrivingControl.tick(client);
			// Both feed WheelForceFeedback's per-tick state (extra resistance /
			// grip vibration) - must run before WheelForceFeedback.tick() itself
			// or FFB always renders one tick stale.
			VehicleDirectionDebug.tick(client);
			TireGripFeedback.tick(client);
			WheelForceFeedback.tick();

			if (pendingAutoCalibration && client.player != null && client.screen == null) {
				pendingAutoCalibration = false;
				client.setScreen(new WheelCalibrationScreen());
			}
		});
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
			if (guid == null) continue;
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
				say(client, "[WheelTest] joystick connected: " + name + " (guid=" + guid + ")");

				WheelProfile profile = WheelConfig.get(guid);
				if ((profile == null || !profile.isDriveable()) && !anyCalibratedJoystickPresent()) {
					pendingAutoCalibration = true;
				}
			}
		}
		for (String guid : connectedGuids) {
			if (!present.contains(guid)) {
				say(client, "[WheelTest] joystick disconnected (guid=" + guid + ")");
			}
		}
		connectedGuids.clear();
		connectedGuids.addAll(present);
	}
}
