package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

/**
 * Drives actual Minecraft input from the wheel/pedals whenever a
 * calibrated wheel is connected - no on/off key, it's just always live.
 * Throttle/brake map to forward/back, steering is a rate control on
 * camera yaw (held-over angle turns the view at a speed proportional to
 * that angle, like a rudder - not a 1:1 position mapping), and the
 * calibrated gear-shift/booster buttons strafe left/right while the
 * special/ERS button jumps.
 *
 * Each key is set to (wheel wants it) OR (actually physically held on
 * keyboard/mouse right now), polled fresh from GLFW every tick rather
 * than trusted from KeyMapping's own bookkeeping - since this class also
 * writes to that same bookkeeping, reading it back would just be reading
 * our own last output, not the real keyboard state, which either latched
 * keys on forever or blocked real WASD input while the wheel was idle.
 */
public class WheelDrivingControl {
	// Turn rate at full lock when the player's sensitivity setting is 100%;
	// WheelProfile.steerSensitivityPercent scales this up or down per device.
	public static final float BASE_YAW_RATE_DEG_PER_SEC = 270f;
	// Throttle/brake are simple on/off keys, so anything between "barely
	// touched" and "floored" would otherwise all mean full speed. Below
	// PEDAL_PWM_LOW the key is just released, above PEDAL_PWM_HIGH it's
	// just held, and in between it's tapped on/off every tick with a duty
	// cycle proportional to pedal position - fine control via rapid
	// tapping instead of a binary key.
	private static final float PEDAL_PWM_LOW = 0.05f;
	private static final float PEDAL_PWM_HIGH = 0.95f;
	// Below this, treat steering as dead-center - a wheel that isn't
	// perfectly still (or a calibration center that's off by a hair)
	// otherwise dribbles tiny yaw deltas every tick even at rest.
	private static final float STEER_DEADZONE = 0.01f;
	// Caps how much elapsed time a single frame can contribute, so a lag
	// spike/stutter (GC pause, chunk load hitch) doesn't turn into one huge
	// catch-up rotation on the frame right after it.
	private static final float MAX_FRAME_DELTA_SECONDS = 0.1f;
	// Look up/down is a fixed-speed convenience binding, not a control
	// input - it doesn't affect driving, so it isn't scaled by the
	// steering sensitivity setting.
	private static final float PITCH_RATE_DEG_PER_SEC = 90f;

	// Tracks real elapsed time between frames so steering can be applied
	// once per rendered frame (like mouse-look) instead of once per game
	// tick (20/s) - that fixed 20 Hz stepping, not a lack of interpolation,
	// is what made rotation feel choppy compared to riding a vehicle.
	private static long lastFrameNanos = -1;
	// Delta-sigma PWM accumulators: each tick adds the target duty cycle
	// and fires a pulse whenever the running total tips past 1.0, which
	// spreads pulses evenly over time for any duty cycle rather than
	// quantizing to a fixed on/off period.
	private static float throttlePwmAccumulator;
	private static float brakePwmAccumulator;
	// setDown() alone only feeds continueAttack() (block-mining hold);
	// entity attacks and other single-click actions only fire from
	// consumeClick(), which is driven by KeyMapping.click()'s static
	// clickCount, not by isDown - so a rising edge needs its own click().
	private static boolean prevWheelAttack;
	// Same rising-edge-only treatment for the convenience bindings below -
	// none of these are meant to repeat every tick while held.
	private static boolean prevWheelSwapHands;
	private static boolean prevWheelViewToggle;
	private static boolean prevWheelHotbarShift;
	// Whether the previous tick was actually driving from the wheel - see the
	// falling-edge release in tick().
	private static boolean wasWheelReady;

	private WheelDrivingControl() {
	}

	/** Whether the forward key is currently being PWM-tapped by the pedal, so vanilla's double-tap-sprint window should be kept disarmed (see LocalPlayerSprintMixin). */
	public static boolean isSuppressingAutoSprintTrigger() {
		return WheelInput.available && WheelInput.throttle > PEDAL_PWM_LOW;
	}

	public static void shutdown(Minecraft client) {
		releaseAll(client);
	}

	private static void releaseAll(Minecraft client) {
		client.options.keyUp.setDown(false);
		client.options.keyDown.setDown(false);
		client.options.keyLeft.setDown(false);
		client.options.keyRight.setDown(false);
		client.options.keyJump.setDown(false);
		client.options.keyAttack.setDown(false);
		client.options.keyUse.setDown(false);
		client.options.keyShift.setDown(false);
		prevWheelAttack = false;
		prevWheelSwapHands = false;
		prevWheelViewToggle = false;
		prevWheelHotbarShift = false;
	}

	public static void tick(Minecraft client) {
		// armedAxisBindings is identity-keyed on InputBinding instances - a
		// fresh device connect/recalibration hands WheelInput.activeProfile
		// (and every InputBinding inside it) brand new objects, so the old
		// ones would otherwise sit in the set forever, never matched by
		// isDown() again and never removed. Clearing on every GUID change
		// keeps that from growing unbounded across a long session.
		String currentGuid = WheelInput.activeGuid;
		if (!java.util.Objects.equals(currentGuid, lastArmedGuid)) {
			armedAxisBindings.clear();
			lastArmedGuid = currentGuid;
		}

		// Don't fight typing/menus with injected movement keys, and don't
		// drive at all while alt-tabbed away - SDL joystick polling keeps
		// working regardless of window focus, so without this a pedal left
		// pressed (or a wheel just off-center) keeps moving/turning the
		// player while the game window isn't even in front.
		if (client.player == null || client.screen != null || !client.isWindowActive()) {
			// Only release keys this class could actually be holding - releasing
			// unconditionally would stomp another mod's setDown() every tick any
			// screen is open, even with no wheel connected, which is exactly what
			// the wheelReady guard further down exists to prevent. Clearing
			// wasWheelReady makes this one-shot on the falling edge for the same
			// reason.
			if (WheelInput.available || wasWheelReady) {
				releaseAll(client);
				wasWheelReady = false;
			}
			return;
		}

		boolean wheelReady = WheelInput.available;
		WheelProfile profile = wheelReady ? WheelInput.activeProfile : null;

		boolean wheelUp = wheelReady && pwmPulse(WheelInput.throttle, true);
		boolean wheelDown = wheelReady && pwmPulse(WheelInput.brake, false);
		// Gear-down and booster are separate physical bindings that both
		// drive strafe-left/A, since which one applies depends on the
		// vehicle the player is currently driving in MCRider.
		boolean wheelLeft = wheelReady && profile != null && (isDown(profile.gearDown) || isDown(profile.booster));
		boolean wheelRight = wheelReady && profile != null && isDown(profile.gearUp);
		boolean wheelJump = wheelReady && profile != null && isDown(profile.special);
		boolean wheelAttack = wheelReady && profile != null && isDown(profile.leftClick);
		boolean wheelUse = wheelReady && profile != null && isDown(profile.rightClick);
		boolean wheelCrouch = wheelReady && profile != null && isDown(profile.crouch);
		boolean wheelSwapHands = wheelReady && profile != null && isDown(profile.swapHands);
		boolean wheelViewToggle = wheelReady && profile != null && isDown(profile.viewToggle);
		boolean wheelHotbarShift = wheelReady && profile != null && isDown(profile.hotbarShift);

		// Vanilla's AFK frame-rate limiter (FramerateLimitTracker) only resets
		// its idle timer from the real GLFW keyboard/mouse callbacks - it never
		// sees the keys we inject below via setDown(), so driving purely by
		// wheel for a while looks exactly like sitting AFK and the game throttles
		// itself mid-drive. Poking it here whenever there's genuine wheel
		// activity (not just "a wheel is connected") keeps that from firing
		// without disabling the AFK limiter for players who are actually idle.
		boolean wheelActive = wheelReady && (wheelLeft || wheelRight || wheelJump || wheelAttack || wheelUse
				|| wheelCrouch || wheelSwapHands || wheelViewToggle || wheelHotbarShift
				|| WheelInput.throttle > PEDAL_PWM_LOW || WheelInput.brake > PEDAL_PWM_LOW
				|| Math.abs(WheelInput.steering) > STEER_DEADZONE);
		if (wheelActive) {
			client.getFramerateLimitTracker().onInputReceived();
		}

		// Only touch these key mappings while a wheel is actually driving -
		// setMerged() re-asserts real physical key state on top of whatever
		// else last set it, which is harmless while the wheel is live (it's
		// only ever OR'd with "wheel wants it"), but with no wheel connected
		// it would still overwrite every tick anything else programmatically
		// held down via setDown() (e.g. an autowalk mod) with just the raw
		// physical read.
		if (wheelReady) {
			setMerged(client, client.options.keyUp, wheelUp);
			setMerged(client, client.options.keyDown, wheelDown);
			setMerged(client, client.options.keyLeft, wheelLeft);
			setMerged(client, client.options.keyRight, wheelRight);
			setMerged(client, client.options.keyJump, wheelJump);
			setMerged(client, client.options.keyAttack, wheelAttack);
			setMerged(client, client.options.keyUse, wheelUse);
			setMerged(client, client.options.keyShift, wheelCrouch);
		} else if (wasWheelReady) {
			// Nothing clears KeyMapping.isDown on its own, so a key this class was
			// holding on the wheel's behalf stays held the moment the block above
			// stops running - unplugging the wheel mid-throttle left the player
			// walking forward until they pressed the real key or opened a screen.
			// One merge pass on the falling edge hands every key back to its
			// physical state; being one-shot rather than per-tick, it still can't
			// stomp another mod's setDown() while no wheel is connected.
			setMerged(client, client.options.keyUp, false);
			setMerged(client, client.options.keyDown, false);
			setMerged(client, client.options.keyLeft, false);
			setMerged(client, client.options.keyRight, false);
			setMerged(client, client.options.keyJump, false);
			setMerged(client, client.options.keyAttack, false);
			setMerged(client, client.options.keyUse, false);
			setMerged(client, client.options.keyShift, false);
		}
		wasWheelReady = wheelReady;

		if (wheelAttack && !prevWheelAttack) {
			KeyMapping.click(KeyBindingHelper.getBoundKeyOf(client.options.keyAttack));
		}
		prevWheelAttack = wheelAttack;

		// Swap-hands and view-toggle just proxy vanilla's own click-counted
		// keybinds (F5/F, whichever the player has them bound to) rather than
		// reimplementing perspective-cycling or offhand-swap logic ourselves -
		// same click() trick as keyAttack above.
		if (wheelSwapHands && !prevWheelSwapHands) {
			KeyMapping.click(KeyBindingHelper.getBoundKeyOf(client.options.keySwapOffhand));
		}
		prevWheelSwapHands = wheelSwapHands;

		if (wheelViewToggle && !prevWheelViewToggle) {
			KeyMapping.click(KeyBindingHelper.getBoundKeyOf(client.options.keyTogglePerspective));
		}
		prevWheelViewToggle = wheelViewToggle;

		// No vanilla keybind cycles the hotbar by one slot, so this sets the
		// selected slot directly - MultiPlayerGameMode.tick() already watches
		// Inventory.getSelectedSlot() every tick and sends
		// ServerboundSetCarriedItemPacket on its own whenever it changes,
		// exactly as it does for a number-key press, so no packet is sent
		// here.
		if (wheelHotbarShift && !prevWheelHotbarShift) {
			Inventory inventory = client.player.getInventory();
			inventory.setSelectedSlot((inventory.getSelectedSlot() + 1) % Inventory.SELECTION_SIZE);
		}
		prevWheelHotbarShift = wheelHotbarShift;

		// The PWM throttle tap pattern looks exactly like rapid double-tapping
		// W, which vanilla reads as "start sprinting" - so while the pedal is
		// actually being used, we take sprint over explicitly instead of
		// letting that double-tap heuristic fire: sprint only once the pedal
		// is floored, otherwise walk speed regardless of tap rate. This runs
		// after vanilla's own tick logic (END_CLIENT_TICK), so it's the last
		// word for this tick and overrides whatever vanilla's detector did.
		if (wheelReady && WheelInput.throttle > PEDAL_PWM_LOW) {
			client.player.setSprinting(WheelInput.throttle >= PEDAL_PWM_HIGH);
		}
	}

	private static float dutyFromPedal(float pedalValue) {
		if (pedalValue <= PEDAL_PWM_LOW) return 0f;
		if (pedalValue >= PEDAL_PWM_HIGH) return 1f;
		return (pedalValue - PEDAL_PWM_LOW) / (PEDAL_PWM_HIGH - PEDAL_PWM_LOW);
	}

	private static boolean pwmPulse(float pedalValue, boolean throttle) {
		float duty = dutyFromPedal(pedalValue);
		float accumulator = (throttle ? throttlePwmAccumulator : brakePwmAccumulator) + duty;
		boolean pulse = accumulator >= 1f;
		if (pulse) accumulator -= 1f;
		if (throttle) {
			throttlePwmAccumulator = accumulator;
		} else {
			brakePwmAccumulator = accumulator;
		}
		return pulse;
	}

	private static void setMerged(Minecraft client, KeyMapping mapping, boolean wheelWants) {
		mapping.setDown(wheelWants || isPhysicallyDown(client, mapping));
	}

	/** Polls the real hardware state of whatever key/mouse-button this mapping is bound to, bypassing our own overrides. */
	private static boolean isPhysicallyDown(Minecraft client, KeyMapping mapping) {
		// getBoundKeyOf() (not getDefaultKey()) so a key remapped in
		// Options > Controls is still recognized here.
		InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
		long windowHandle = client.getWindow().getWindow();
		if (key.getType() == InputConstants.Type.KEYSYM) {
			return key.getValue() >= 0 && InputConstants.isKeyDown(windowHandle, key.getValue());
		}
		if (key.getType() == InputConstants.Type.MOUSE) {
			return key.getValue() >= 0 && GLFW.glfwGetMouseButton(windowHandle, key.getValue()) == GLFW.GLFW_PRESS;
		}
		return false;
	}

	/** Called every rendered frame (not every game tick) so steering turns the view as smoothly as mouse-look does. */
	public static void tickRotation(Minecraft client) {
		if (!WheelInput.available || client.player == null || client.screen != null || !client.isWindowActive()) {
			lastFrameNanos = -1;
			return;
		}

		long now = System.nanoTime();
		if (lastFrameNanos < 0) {
			lastFrameNanos = now;
			return;
		}
		float deltaSeconds = Math.min(MAX_FRAME_DELTA_SECONDS, (now - lastFrameNanos) / 1_000_000_000f);
		lastFrameNanos = now;

		WheelProfile profile = WheelInput.activeProfile;

		float yawDelta = 0f;
		if (Math.abs(WheelInput.steering) > STEER_DEADZONE) {
			float sensitivityPercent = profile != null ? profile.steerSensitivityPercent : 100f;
			float maxYawRate = BASE_YAW_RATE_DEG_PER_SEC * sensitivityPercent / 100f;
			yawDelta = WheelInput.steering * maxYawRate * deltaSeconds;
		}

		float pitchDelta = 0f;
		if (profile != null) {
			if (isDown(profile.lookUp)) pitchDelta -= PITCH_RATE_DEG_PER_SEC * deltaSeconds;
			if (isDown(profile.lookDown)) pitchDelta += PITCH_RATE_DEG_PER_SEC * deltaSeconds;
		}

		if (yawDelta != 0f || pitchDelta != 0f) {
			// Entity.turn(yRot, xRot) multiplies each argument by 0.15 internally
			// (vanilla's passenger-turn convention) and, unlike setYRot()/setXRot(),
			// also advances yRotO/xRotO by that same amount. Without that, yRotO
			// only snaps forward once per tick while yRot itself grows every
			// render frame, so the camera's tick-to-tick lerp was chasing a
			// target that had already moved on - a per-tick judder riding on
			// top of the intended per-frame smoothness. It also clamps xRot to
			// +-90 the same way the old manual Mth.clamp call did.
			client.player.turn(yawDelta / 0.15, pitchDelta / 0.15);
		}
	}

	private static boolean isDown(InputBinding binding) {
		if (binding == null || WheelInput.activeGuid == null) return false;

		if (binding.buttonIndex >= 0) {
			if (binding.buttonIndex >= SdlJoystickReader.buttonCount()) return false;
			// SDL, not GLFW - see SdlJoystickReader's doc comment. WheelInput.tick()
			// keeps the SDL handle open/updated for whichever device is active.
			return SdlJoystickReader.buttonDown(binding.buttonIndex);
		}

		if (binding.hatIndex >= 0) {
			if (binding.hatIndex >= SdlJoystickReader.hatCount()) return false;
			// Bitwise AND (not equality) so a diagonal reading, which ORs two
			// direction bits together, still counts as "down" for either of them.
			return (SdlJoystickReader.hatValue(binding.hatIndex) & binding.hatDirection) != 0;
		}

		if (binding.axisIndex >= 0) {
			if (binding.axisIndex >= SdlJoystickReader.axisCount()) return false;
			float span = binding.axisPressed - binding.axisReleased;
			if (Math.abs(span) < 1e-4f) return false;
			float t = (SdlJoystickReader.axisValue(binding.axisIndex) - binding.axisReleased) / span;
			boolean pressed = t > 0.5f;

			// Some pedal hardware reports a stale/neutral raw value for an
			// axis until it's actually been physically pressed at least once
			// after the device (re)connects - on a fresh world join that can
			// read as "already pressed" and hold a strafe key down with
			// nobody touching it. Stay unarmed (never report "down") until a
			// genuinely released reading is seen, so a false-positive
			// startup reading can't get stuck; one real press-then-release
			// of the pedal is what "fixes" it, since that's what finally
			// gives us a real released sample to arm on.
			if (!pressed) {
				armedAxisBindings.add(binding);
			}
			return pressed && armedAxisBindings.contains(binding);
		}

		return false;
	}

	// Identity-keyed: InputBinding has no equals()/hashCode() override, so
	// default reference identity is exactly what's wanted here anyway.
	private static final java.util.Set<InputBinding> armedAxisBindings =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	// GUID armedAxisBindings was last cleared for - see the top of tick().
	private static String lastArmedGuid;
}
