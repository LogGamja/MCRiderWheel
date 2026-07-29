package com.mcrider.wheeltest.client;

import com.mcrider.wheeltest.client.sdl.SdlJoystickReader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Device-agnostic calibration wizard: instead of hardcoding axis layouts
 * per wheel model, the player performs each action (center, full left,
 * full right, pedals) and we figure out which axis moved and by how much.
 *
 * {@link Scope} lets the wizard cover just the wheel, just the pedals, or
 * just the button mappings, re-running only that section while leaving
 * whatever else is already saved for this device untouched.
 */
public class WheelCalibrationScreen extends Screen {

	public enum Scope {
		FULL, WHEEL_ONLY, PEDALS_ONLY, BUTTONS_ONLY;

		boolean includesWheel() {
			return this == FULL || this == WHEEL_ONLY;
		}

		boolean includesPedals() {
			return this == FULL || this == PEDALS_ONLY;
		}

		boolean includesButtons() {
			return this == FULL || this == BUTTONS_ONLY;
		}
	}

	private enum Step {
		INTRO, CENTER, LEFT, RIGHT, REF90, LOCK_RANGE_SELECT, THROTTLE_UP, THROTTLE_DOWN, BRAKE_UP, BRAKE_DOWN,
		BUTTON_NOISE_SAMPLE, GEAR_DOWN_BUTTON, GEAR_UP_BUTTON, BOOSTER_BUTTON, SPECIAL_BUTTON, LEFT_CLICK_BUTTON,
		RIGHT_CLICK_BUTTON, LOOK_UP_BUTTON, LOOK_DOWN_BUTTON, DONE
	}

	/** Axis must move at least this far from its baseline to count as a deliberate press (filters out drift/noise). */
	private static final float AXIS_CAPTURE_DELTA = 0.3f;

	/** Degree presets offered on the lock-range picker; -1 means "use the full measured lock". */
	private static final float[] LOCK_RANGE_PRESETS = {90f, 180f, 270f, 450f, 900f, -1f};

	// Max tick-to-tick wobble an axis may have and still count as "at rest" -
	// used only to keep axisBaseline tracking an axis's live resting point
	// (see checkInputCapture), not to gate how long a press must be held.
	private static final float CAPTURE_JITTER_BAND = 0.05f;

	// Threshold for the *raw* axis diagnostic line only (see
	// updateRawEventDiagnostic) - deliberately unrelated to
	// AXIS_CAPTURE_DELTA/isExcludedAxis, so a physically moving axis always
	// shows up here even if it's excluded or too small to actually capture.
	// Without this, a spare-pedal axis that's excluded (already claimed as
	// steer/throttle/brake, or flagged noisy) would look indistinguishable
	// from one SDL isn't reading at all - both showed nothing on "최근 아날로그".
	private static final float AXIS_DIAGNOSTIC_DELTA = 0.05f;

	// On a device that combines many peripherals into one huge button/axis
	// set, some channels can misbehave right as the wizard opens (driver/
	// virtual-joystick-layer startup quirks). BUTTON_NOISE_SAMPLE_NANOS is a
	// dedicated "hands off, watching for real" window before the per-control
	// steps start: whatever moves at all during it gets permanently excluded
	// (see noisyButtons/noisyAxes), rather than re-litigated on every future
	// tick. This used to be 30s and required multi-tick-hold + explicit
	// confirm on top of it to work around a GLFW/DirectInput bug that
	// corrupted button reads past index 32 on high-button-count wheels; now
	// that button reads go through SDL instead (see SdlJoystickReader) that
	// bug no longer applies, so capture is a plain edge trigger and this
	// window is just a shorter startup sanity pass.
	private static final long BUTTON_NOISE_SAMPLE_NANOS = 10_000_000_000L; // 10 seconds

	private static final int[] HAT_DIRECTION_BITS = {
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_UP,
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_RIGHT,
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_DOWN,
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_LEFT,
	};
	// Index-matched with HAT_DIRECTION_BITS - "HAT"/"POV" is SDL/hardware
	// jargon for what's really just a directional button (a D-pad direction
	// on a wheel rim, e.g. a hat switch), so the UI calls it "방향 버튼"
	// everywhere instead.
	private static final String[] HAT_DIRECTION_NAMES = {"위", "오른쪽", "아래", "왼쪽"};

	/** Korean label for a HAT bitmask - a single direction (one of HAT_DIRECTION_BITS) or a diagonal OR of two. */
	private static String hatDirectionName(int bitmask) {
		if (bitmask == io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_CENTERED) return "중앙";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < HAT_DIRECTION_BITS.length; i++) {
			if ((bitmask & HAT_DIRECTION_BITS[i]) != 0) {
				if (sb.length() > 0) sb.append("+");
				sb.append(HAT_DIRECTION_NAMES[i]);
			}
		}
		return sb.length() > 0 ? sb.toString() : "알 수 없음";
	}

	private final Scope scope;
	private final Screen parent;
	private Step step = Step.INTRO;
	private String selectedGuid;

	private float[] centerSnap;
	private float[] leftSnap;
	private float[] rightSnap;
	private float[] ref90Snap;
	private float desiredLockDeg = -1f; // -1 = use the full measured lock, no clamping
	private float[] throttleUpSnap;
	private float[] throttleDownSnap;
	private float[] brakeUpSnap;
	private float[] brakeDownSnap;

	private WheelProfile profile;
	private float[] axisBaseline;
	// Peak |deviation from axisBaseline| (and the raw value at that peak)
	// seen per axis since it last settled back to rest - see the axis
	// section of checkInputCapture() for why the *peak* is what gets
	// captured as axisPressed rather than whatever the axis reads on
	// whichever tick happens to be live when a candidate is chosen.
	private float[] axisPeakDeviation;
	private float[] axisPeakValue;
	private final java.util.Set<Integer> claimedAxes = new java.util.HashSet<>();
	// Populated once by BUTTON_NOISE_SAMPLE and never touched again -
	// indices that moved at all during the hands-off sampling window, so
	// they're never reconsidered as a candidate no matter how they later
	// happen to settle. See BUTTON_NOISE_SAMPLE_NANOS.
	private final java.util.Set<Integer> noisyButtons = new java.util.HashSet<>();
	private final java.util.Set<Integer> noisyAxes = new java.util.HashSet<>();
	private long noiseSampleStartNanos = -1;
	// Edge-detection state for button/HAT capture (see checkInputCapture) -
	// sized/seeded once for the whole button-mapping phase, not reset per
	// step, so a button still held from the moment it was just captured
	// doesn't immediately re-trigger the next step.
	private boolean[] prevCaptureButtonsDown;
	private byte[] prevCaptureHats;
	// Set the instant a candidate is detected, and re-set (overwriting
	// whatever was pending) if a *different* input edge-triggers while still
	// awaiting confirm - so pressing the wrong control and then the right one
	// just re-prompts for the new one instead of getting stuck. Unlike the
	// old confirm gate, this doesn't require the candidate to still be held:
	// the player can press-and-release, then click [확인] separately.
	private InputBinding pendingBinding;
	private boolean awaitingConfirm;
	// Per-axis peak deviation as of the moment a button/HAT candidate became
	// pending - the bar an axis has to clear to take that candidate over. See
	// the nonAxisPending branch in checkInputCapture().
	private float[] axisOverrideFloor;

	// Raw diagnostics for figuring out which physical control an
	// unrecognized input actually is - runs alongside (and independently of)
	// the gating in checkInputCapture, purely for display.
	private boolean[] prevButtonsDown;
	private byte[] prevHats;
	private float[] prevAxisValues;
	private String lastButtonEventText = "(없음)";
	private String lastAxisEventText = "(없음)";
	private String lastHatEventText = "(없음)";
	private InputBinding gearDown = new InputBinding();
	private InputBinding gearUp = new InputBinding();
	private InputBinding booster = new InputBinding();
	private InputBinding special = new InputBinding();
	private InputBinding leftClick = new InputBinding();
	private InputBinding rightClick = new InputBinding();
	private InputBinding lookUp = new InputBinding();
	private InputBinding lookDown = new InputBinding();

	private Button nextButton;
	private Button skipButton;
	private Button exitButton;
	private final Button[] lockRangeButtons = new Button[LOCK_RANGE_PRESETS.length];
	// One button per device shown on the INTRO step, letting the player
	// override findFirstDeviceGuid()'s auto-pick - see selectDevice().
	private final java.util.List<Button> deviceButtons = new java.util.ArrayList<>();
	// Caps how many of SdlJoystickReader's enumerated devices get their own
	// selectable button, so a machine with an unusually large number of
	// enumerated joysticks (virtual devices, etc.) can't push these past
	// nextButton's fixed position below - deviceListLines() in render()
	// still lists every device as plain text regardless of this cap.
	private static final int MAX_SELECTABLE_DEVICE_BUTTONS = 6;

	public WheelCalibrationScreen() {
		this(Scope.FULL, null);
	}

	public WheelCalibrationScreen(Scope scope, Screen parent) {
		super(Component.literal("MCRider Wheel Calibration"));
		this.scope = scope;
		this.parent = parent;
	}

	@Override
	protected void init() {
		ensureDeviceSelected();
		ensureProfile(); // load any existing steer/pedal axes early so button capture can exclude them

		nextButton = Button.builder(Component.literal("다음"), b -> {
					if (awaitingConfirm) {
						confirmPendingBinding();
					} else {
						advance();
					}
				})
				.bounds(width / 2 - 100, height / 2 + 40, 200, 20)
				.build();
		addRenderableWidget(nextButton);

		skipButton = Button.builder(Component.literal("건너뛰기"), b -> skipCurrentStep())
				.bounds(width / 2 - 100, height / 2 + 65, 200, 20)
				.build();
		addRenderableWidget(skipButton);

		// Y is re-set every tick in updateWidgetVisibility() - [건너뛰기] only
		// exists on some steps, and leaving this at its "skip is showing"
		// position the rest of the time strands an empty row between it and
		// [다음].
		exitButton = Button.builder(Component.literal("나가기"), b -> onClose())
				.bounds(width / 2 - 100, height / 2 + 90, 200, 20)
				.build();
		addRenderableWidget(exitButton);

		int cols = 3;
		int btnWidth = 95;
		int btnHeight = 20;
		int gap = 10;
		int gridWidth = cols * btnWidth + (cols - 1) * gap;
		for (int i = 0; i < LOCK_RANGE_PRESETS.length; i++) {
			float deg = LOCK_RANGE_PRESETS[i];
			String label = deg < 0 ? "실측 풀 락 그대로" : (int) deg + "도" + (deg == 180f ? " (추천)" : "");
			int col = i % cols;
			int row = i / cols;
			int x = width / 2 - gridWidth / 2 + col * (btnWidth + gap);
			int y = height / 2 + (row * (btnHeight + gap));
			Button btn = Button.builder(Component.literal(label), b -> selectLockRange(deg))
					.bounds(x, y, btnWidth, btnHeight)
					.build();
			lockRangeButtons[i] = btn;
			addRenderableWidget(btn);
		}

		// One selectable button per device SDL currently enumerates. Clicking one
		// only marks it as chosen; [다음] is what starts the wizard (see
		// selectDevice()). Also how a second/different wheel gets calibrated at
		// all, since findFirstDeviceGuid() otherwise always prefers whatever
		// WheelInput already recognizes.
		//
		// init() runs again on every window resize (Screen.resize() clears the
		// widget list and re-inits), so this own-list has to be cleared too -
		// otherwise it accumulates orphaned Button objects from every previous
		// layout, which updateWidgetVisibility() then walks each tick forever.
		deviceButtons.clear();
		int introPromptLines = promptFor(Step.INTRO).split("\n").length;
		int devBtnWidth = Math.min(280, width - 20);
		int devBtnHeight = 20; // vanilla's own button height (e.g. this screen's own [다음]/[취소]/[건너뛰기])
		int devBtnGap = 4;
		// Straight under the prompt now that the device *names* are these
		// buttons rather than a separate text list above them.
		int devStartY = (height / 2 - 50) + introPromptLines * 10 + 10;
		// Capped by the room actually available above [다음]'s fixed position,
		// not just by MAX_SELECTABLE_DEVICE_BUTTONS - a machine enumerating
		// several joysticks (virtual devices, a wheel that splits into multiple
		// entries) would otherwise run this column straight through that button.
		int roomForButtons = Math.max(0, (nextButton.getY() - devBtnGap - devStartY) / (devBtnHeight + devBtnGap));
		int deviceCount = Math.min(Math.min(SdlJoystickReader.deviceCount(), MAX_SELECTABLE_DEVICE_BUTTONS), roomForButtons);
		if (deviceCount > 0) {
			for (int i = 0; i < deviceCount; i++) {
				String guid = SdlJoystickReader.deviceGuid(i);
				if (guid == null) continue;
				String name = SdlJoystickReader.deviceName(i);
				String label = (name.isEmpty() ? ("장치 " + (i + 1)) : name)
						+ (guid.equalsIgnoreCase(selectedGuid) ? " [선택됨]" : "");
				Button btn = Button.builder(Component.literal(label), b -> selectDevice(guid))
						.bounds(width / 2 - devBtnWidth / 2, devStartY + i * (devBtnHeight + devBtnGap), devBtnWidth, devBtnHeight)
						.build();
				deviceButtons.add(btn);
				addRenderableWidget(btn);
			}
		}

		// Without this, the lock-range buttons (added visible above) sit
		// on screen - overlapping [다음] - for up to one tick after init()
		// before tick() gets a chance to hide them for the INTRO step.
		updateWidgetVisibility();
	}

	@Override
	public void onClose() {
		if (parent != null) {
			minecraft.setScreen(parent);
		} else {
			super.onClose();
		}
	}

	@Override
	public void tick() {
		super.tick();
		updateWidgetVisibility();
		ensureDeviceSelected();
		SdlJoystickReader.update();
		// Retried every tick, not just from init() - if SDL hadn't finished
		// enumerating the device yet the first time init() ran (same
		// hotplug-detection lag ensureDeviceSelected() already retries for),
		// profile stayed null for the rest of the button-mapping phase and
		// isExcludedAxis() had nothing to exclude the steer/throttle/brake
		// axes with, letting them get captured as misc buttons. Cheap no-op
		// once profile is already loaded.
		ensureProfile();
		if (step == Step.BUTTON_NOISE_SAMPLE) {
			runNoiseSample();
		} else {
			checkInputCapture();
		}
	}

	private void updateWidgetVisibility() {
		boolean skippable = isButtonCaptureStep(step) || step == Step.BUTTON_NOISE_SAMPLE;
		skipButton.visible = skippable;
		skipButton.active = skippable;
		// Close the gap [건너뛰기] leaves behind on the steps where it's hidden.
		exitButton.setY(skippable ? height / 2 + 90 : height / 2 + 65);

		// Auto-detected on press, or picked from the lock-range buttons, or
		// auto-timed - clicking [다음] through these steps wouldn't mean
		// anything. The one exception is awaitingConfirm: [다음] is exactly
		// how the player confirms a just-detected candidate (see
		// confirmPendingBinding()), so it needs to reappear - relabeled -
		// during that pause.
		boolean waitingForOtherInput = isButtonCaptureStep(step) || step == Step.LOCK_RANGE_SELECT
				|| step == Step.BUTTON_NOISE_SAMPLE;
		nextButton.visible = !waitingForOtherInput || awaitingConfirm;
		// On INTRO, [다음] is what commits to the picked device - with nothing
		// connected (so nothing selectable) there's no device to calibrate and
		// it would just walk an empty wizard that never saves anything
		// (completeAndSave() no-ops with a null profile). Re-checked every tick
		// since a device can appear while this screen is already open.
		boolean introHasDevice = step != Step.INTRO || selectedGuid != null;
		nextButton.active = (!waitingForOtherInput || awaitingConfirm) && introHasDevice;
		nextButton.setMessage(Component.literal(awaitingConfirm ? "확인" : "다음"));

		boolean onLockRangeStep = step == Step.LOCK_RANGE_SELECT;
		for (Button b : lockRangeButtons) {
			b.visible = onLockRangeStep;
			b.active = onLockRangeStep;
		}

		boolean onIntro = step == Step.INTRO;
		for (Button b : deviceButtons) {
			b.visible = onIntro;
			b.active = onIntro;
		}
	}

	// --- scope-aware section ordering -------------------------------------------------

	private Step afterIntro() {
		if (scope.includesWheel()) return Step.CENTER;
		if (scope.includesPedals()) return Step.THROTTLE_UP;
		if (scope.includesButtons()) return Step.BUTTON_NOISE_SAMPLE;
		return Step.DONE;
	}

	private Step afterWheelGroup() {
		if (scope.includesPedals()) return Step.THROTTLE_UP;
		if (scope.includesButtons()) return Step.BUTTON_NOISE_SAMPLE;
		return Step.DONE;
	}

	private Step afterPedalGroup() {
		if (scope.includesButtons()) return Step.BUTTON_NOISE_SAMPLE;
		return Step.DONE;
	}

	/** Every transition that might land on DONE goes through here so the save always happens exactly once. */
	private void goTo(Step next) {
		if (next == Step.DONE) {
			completeAndSave();
		}
		step = next;
	}

	private boolean isButtonCaptureStep(Step s) {
		return s == Step.GEAR_DOWN_BUTTON || s == Step.GEAR_UP_BUTTON || s == Step.BOOSTER_BUTTON
				|| s == Step.SPECIAL_BUTTON || s == Step.LEFT_CLICK_BUTTON || s == Step.RIGHT_CLICK_BUTTON
				|| s == Step.LOOK_UP_BUTTON || s == Step.LOOK_DOWN_BUTTON;
	}

	/**
	 * Seeded exactly once for the whole button-mapping phase - an axis has
	 * no clean "at rest" value once it's mid-motion, so re-arming it right
	 * when a step advances - i.e. while a pedal is still on its way back up
	 * from being pressed - would look exactly like a brand new press against
	 * the fresh baseline, cascading the same pedal through every remaining
	 * step in one motion. Past this initial seed, {@link #checkInputCapture}
	 * keeps each axis's baseline continuously refreshed while it's at rest
	 * anyway (see its comment), so this only matters for whatever an axis's
	 * value happens to be at the very first tick of the phase.
	 */
	private void captureAxisBaselineOnce() {
		if (axisBaseline != null) return;
		axisBaseline = snapshot();
	}

	/** Axes already used for steering/pedals, already claimed by an earlier button-mapping step, or flagged noisy by BUTTON_NOISE_SAMPLE are excluded so they can't be mistaken for a different input. */
	private boolean isExcludedAxis(int axis) {
		if (claimedAxes.contains(axis) || noisyAxes.contains(axis)) return true;
		return profile != null && (axis == profile.steerAxis || axis == profile.throttleAxis || axis == profile.brakeAxis);
	}

	/**
	 * Records the most recent raw button/hat transition, independent of
	 * (and running alongside) the gated capture logic below - purely for
	 * display, so a control that never actually gets captured (e.g. one
	 * this class has no support for at all) still shows up as *something*
	 * happening rather than silent nothing.
	 */
	private void updateRawEventDiagnostic() {
		if (!SdlJoystickReader.isOpen()) return;

		int buttons = SdlJoystickReader.buttonCount();
		if (prevButtonsDown == null || prevButtonsDown.length != buttons) {
			prevButtonsDown = new boolean[buttons];
		}
		for (int i = 0; i < buttons; i++) {
			boolean down = SdlJoystickReader.buttonDown(i);
			// Shown even when noisyButtons excludes it from real capture -
			// same reasoning as the axis branch below (isExcludedAxis check):
			// silently hiding a noise-filtered button here would make it
			// indistinguishable from one that isn't triggering at all, which
			// defeats this diagnostic's whole purpose of showing *something*
			// the instant a control is touched.
			if (down && !prevButtonsDown[i]) {
				lastButtonEventText = (i + 1) + "번 버튼 눌림"
						+ (noisyButtons.contains(i) ? ". 노이즈로 제외된 버튼입니다" : "");
			}
			prevButtonsDown[i] = down;
		}

		int hats = SdlJoystickReader.hatCount();
		if (prevHats == null || prevHats.length != hats) {
			prevHats = new byte[hats];
		}
		for (int i = 0; i < hats; i++) {
			byte value = SdlJoystickReader.hatValue(i);
			if (!SdlJoystickReader.isHatCentered(value) && value != prevHats[i]) {
				lastHatEventText = (i + 1) + "번 방향 버튼 " + hatDirectionName(value);
			}
			prevHats[i] = value;
		}

		int axes = SdlJoystickReader.axisCount();
		if (prevAxisValues == null || prevAxisValues.length != axes) {
			prevAxisValues = new float[axes];
			for (int i = 0; i < axes; i++) prevAxisValues[i] = SdlJoystickReader.axisValue(i);
		}
		for (int i = 0; i < axes; i++) {
			float value = SdlJoystickReader.axisValue(i);
			if (Math.abs(value - prevAxisValues[i]) > AXIS_DIAGNOSTIC_DELTA) {
				lastAxisEventText = (i + 1) + "번 아날로그 변화 (값 " + String.format("%.2f", value) + ")"
						+ (isExcludedAxis(i) ? ". 이미 사용 중이거나 제외된 아날로그입니다" : "");
			}
			prevAxisValues[i] = value;
		}
	}

	/**
	 * Runs for BUTTON_NOISE_SAMPLE_NANOS while the player is asked to leave
	 * the device alone: anything that so much as twitches during this
	 * window - a button that's ever seen down, or an axis that ever
	 * deviates past AXIS_CAPTURE_DELTA from its value when sampling began -
	 * goes into noisyButtons/noisyAxes and is permanently excluded from the
	 * real capture steps that follow, regardless of how it later settles.
	 * Unlike axisBaseline's continuous refresh during real capture (which
	 * deliberately forgives an axis once it returns to rest), this pass
	 * never forgives - the whole point is to catch things that only
	 * misbehave *some* of the time.
	 */
	private void runNoiseSample() {
		if (!SdlJoystickReader.isOpen()) return;
		captureAxisBaselineOnce();
		updateRawEventDiagnostic();

		long now = System.nanoTime();
		if (noiseSampleStartNanos < 0) {
			noiseSampleStartNanos = now;
		}

		int buttonCount = SdlJoystickReader.buttonCount();
		for (int i = 0; i < buttonCount; i++) {
			if (SdlJoystickReader.buttonDown(i)) {
				noisyButtons.add(i);
			}
		}

		if (axisBaseline != null && axisBaseline.length > 0) {
			int count = Math.min(SdlJoystickReader.axisCount(), axisBaseline.length);
			for (int i = 0; i < count; i++) {
				if (isExcludedAxis(i)) continue;
				if (Math.abs(SdlJoystickReader.axisValue(i) - axisBaseline[i]) > AXIS_CAPTURE_DELTA) {
					noisyAxes.add(i);
				}
			}
		}

		if (now - noiseSampleStartNanos >= BUTTON_NOISE_SAMPLE_NANOS) {
			goTo(Step.GEAR_DOWN_BUTTON);
		}
	}

	/**
	 * Auto-detects the next button-mapping capture as a plain edge trigger:
	 * whichever non-noisy button, HAT direction, or axis is the first to
	 * newly cross into "pressed" this tick becomes the pending candidate,
	 * shown to the player for an explicit [확인] before it's actually
	 * assigned (see confirmPendingBinding()) - no need to still be holding
	 * it down at that point, unlike the old confirm gate. This used to also
	 * require a multi-tick hold to work around a GLFW/DirectInput bug that
	 * corrupted button reads past index 32 on high-button-count wheels - now
	 * that button (and HAT/axis) reads go through SDL instead (see
	 * SdlJoystickReader) that bug no longer applies, so detection itself is
	 * a simple edge trigger; only the human confirm step remains.
	 */
	private void checkInputCapture() {
		if (!isButtonCaptureStep(step) || !SdlJoystickReader.isOpen()) return;
		captureAxisBaselineOnce();
		updateRawEventDiagnostic();

		int buttonCount = SdlJoystickReader.buttonCount();
		if (prevCaptureButtonsDown == null || prevCaptureButtonsDown.length != buttonCount) {
			boolean[] seeded = new boolean[buttonCount];
			for (int i = 0; i < buttonCount; i++) seeded[i] = SdlJoystickReader.buttonDown(i);
			prevCaptureButtonsDown = seeded;
		}
		for (int i = 0; i < buttonCount; i++) {
			boolean down = SdlJoystickReader.buttonDown(i);
			boolean pressedEdge = down && !prevCaptureButtonsDown[i];
			prevCaptureButtonsDown[i] = down;
			if (pressedEdge && !noisyButtons.contains(i)) {
				InputBinding binding = new InputBinding();
				binding.buttonIndex = i;
				snapshotAxisOverrideFloor();
				pendingBinding = binding;
				awaitingConfirm = true;
				return;
			}
		}

		int hatCount = SdlJoystickReader.hatCount();
		if (prevCaptureHats == null || prevCaptureHats.length != hatCount) {
			byte[] seeded = new byte[hatCount];
			for (int i = 0; i < hatCount; i++) seeded[i] = SdlJoystickReader.hatValue(i);
			prevCaptureHats = seeded;
		}
		for (int i = 0; i < hatCount; i++) {
			byte value = SdlJoystickReader.hatValue(i);
			byte prev = prevCaptureHats[i];
			prevCaptureHats[i] = value;
			for (int bit : HAT_DIRECTION_BITS) {
				if ((value & bit) != 0 && (prev & bit) == 0) {
					InputBinding binding = new InputBinding();
					binding.hatIndex = i;
					binding.hatDirection = bit;
					snapshotAxisOverrideFloor();
					pendingBinding = binding;
					awaitingConfirm = true;
					return;
				}
			}
		}

		// Axes: pick whichever unexcluded axis deviates *most* from baseline
		// (rather than the first past the threshold), so a genuinely
		// full-travel press wins over a smaller spike on an unrelated axis.
		//
		// axisBaseline itself is kept live here, not just seeded once: any
		// axis currently within CAPTURE_JITTER_BAND of its own stored
		// baseline is considered "at rest" and has its baseline refreshed to
		// its current value. Otherwise an axis that simply isn't centered at
		// the single instant the whole phase's baseline snapshot happened to
		// be taken - permanently sitting elevated, never actually moving -
		// would look exactly like a real press against that stale snapshot
		// forever. Refreshing while at rest means only genuine motion away
		// from wherever an axis was actually sitting can trigger a capture.
		//
		// The candidate's value is the *peak* deviation seen since the axis
		// last sat at rest, not just whatever it happens to read on the tick
		// a candidate is (re-)selected. A press-then-release (the wizard
		// explicitly doesn't require still holding it at confirm time) spends
		// its last few ticks above AXIS_CAPTURE_DELTA sliding back down
		// through that threshold on the way to rest - capturing the live
		// value on one of those ticks instead of the true peak could grab
		// something barely over the threshold rather than near full travel,
		// which then makes isDown()'s pressed/released midpoint absurdly
		// sensitive at runtime (see InputBinding/WheelDrivingControl.isDown).
		if (axisBaseline != null && axisBaseline.length > 0) {
			int count = Math.min(SdlJoystickReader.axisCount(), axisBaseline.length);
			if (axisPeakDeviation == null || axisPeakDeviation.length != axisBaseline.length) {
				axisPeakDeviation = new float[axisBaseline.length];
				axisPeakValue = new float[axisBaseline.length];
			}
			for (int i = 0; i < count; i++) {
				if (isExcludedAxis(i)) continue;
				float current = SdlJoystickReader.axisValue(i);
				float deviation = Math.abs(current - axisBaseline[i]);
				if (deviation <= CAPTURE_JITTER_BAND) {
					axisBaseline[i] = current;
					axisPeakDeviation[i] = 0f; // back at rest - forget the peak so the next real press starts fresh
				} else if (deviation > axisPeakDeviation[i]) {
					axisPeakDeviation[i] = deviation;
					axisPeakValue[i] = current;
				}
			}
		}

		// With a button/HAT candidate already pending, an axis has to show
		// motion *new since that candidate was captured* to take it over - not
		// merely be above the threshold. An axis that's simply been sitting
		// elevated since some earlier tick (a rotary dial with no spring
		// return, left turned away from rest) never returns to rest, so its
		// peak deviation stays high forever; letting that count would have it
		// steal the candidate back every tick, and no button could ever be
		// confirmed on such a device. Measuring against the peak recorded at
		// capture time (see snapshotAxisOverrideFloor) leaves a stuck axis
		// unable to grow past its own floor, while a pedal the player actually
		// presses afterwards clears it easily - which is what makes correcting
		// a mis-pressed button with an analog input possible.
		boolean nonAxisPending = awaitingConfirm && pendingBinding != null && pendingBinding.axisIndex < 0;

		int axisCandidate = -1;
		float axisCandidateValue = 0f;
		if (axisPeakDeviation != null) {
			float bestDeviation = AXIS_CAPTURE_DELTA;
			int count = Math.min(SdlJoystickReader.axisCount(), axisPeakDeviation.length);
			for (int i = 0; i < count; i++) {
				if (isExcludedAxis(i)) continue;
				if (nonAxisPending) {
					float floor = axisOverrideFloor != null && i < axisOverrideFloor.length ? axisOverrideFloor[i] : 0f;
					if (axisPeakDeviation[i] <= floor + AXIS_CAPTURE_DELTA) continue;
				}
				if (axisPeakDeviation[i] > bestDeviation) {
					bestDeviation = axisPeakDeviation[i];
					axisCandidate = i;
					axisCandidateValue = axisPeakValue[i];
				}
			}
		}
		if (axisCandidate >= 0) {
			lastAxisEventText = (axisCandidate + 1) + "번 아날로그 변화 (값 " + String.format("%.2f", axisCandidateValue) + ")";
			InputBinding binding = new InputBinding();
			binding.axisIndex = axisCandidate;
			binding.axisReleased = axisBaseline[axisCandidate];
			binding.axisPressed = axisCandidateValue;
			// Not added to claimedAxes yet - only once actually confirmed
			// (see confirmPendingBinding()), so an axis that's detected but
			// then skipped/replaced by a different candidate doesn't stay
			// permanently excluded for nothing.
			pendingBinding = binding;
			awaitingConfirm = true;
		}
	}

	/**
	 * Records where every axis's peak deviation already stood as a button/HAT
	 * candidate is captured, so a later axis press is judged on how much it
	 * moved *after* that point rather than on its absolute reading.
	 */
	private void snapshotAxisOverrideFloor() {
		if (axisPeakDeviation == null) {
			axisOverrideFloor = null;
			return;
		}
		axisOverrideFloor = axisPeakDeviation.clone();
	}

	/** Player clicked [확인] while a candidate was pending - finalize it. */
	private void confirmPendingBinding() {
		if (!awaitingConfirm || pendingBinding == null) return;
		InputBinding binding = pendingBinding;
		if (binding.axisIndex >= 0) {
			claimedAxes.add(binding.axisIndex);
		}
		pendingBinding = null;
		awaitingConfirm = false;
		assignAndAdvance(binding);
	}

	/** Human-readable description of a captured control, for the confirm prompt. */
	private String describeBinding(InputBinding b) {
		if (b.buttonIndex >= 0) return (b.buttonIndex + 1) + "번 버튼";
		if (b.hatIndex >= 0) return (b.hatIndex + 1) + "번 방향 버튼 (" + hatDirectionName(b.hatDirection) + ")";
		if (b.axisIndex >= 0) return (b.axisIndex + 1) + "번 아날로그";
		return "";
	}

	private void assignAndAdvance(InputBinding binding) {
		switch (step) {
			case GEAR_DOWN_BUTTON -> {
				gearDown = binding;
				step = Step.GEAR_UP_BUTTON;
			}
			case GEAR_UP_BUTTON -> {
				gearUp = binding;
				step = Step.BOOSTER_BUTTON;
			}
			case BOOSTER_BUTTON -> {
				booster = binding;
				step = Step.SPECIAL_BUTTON;
			}
			case SPECIAL_BUTTON -> {
				special = binding;
				step = Step.LEFT_CLICK_BUTTON;
			}
			case LEFT_CLICK_BUTTON -> {
				leftClick = binding;
				step = Step.RIGHT_CLICK_BUTTON;
			}
			case RIGHT_CLICK_BUTTON -> {
				rightClick = binding;
				step = Step.LOOK_UP_BUTTON;
			}
			case LOOK_UP_BUTTON -> {
				lookUp = binding;
				step = Step.LOOK_DOWN_BUTTON;
			}
			case LOOK_DOWN_BUTTON -> {
				lookDown = binding;
				computeButtonFields();
				goTo(Step.DONE);
			}
			default -> {
			}
		}
	}

	private void selectLockRange(float degrees) {
		desiredLockDeg = degrees;
		computeSteerFields();
		goTo(afterWheelGroup());
	}

	/**
	 * Leaves whatever binding is already sitting in the field for this step
	 * untouched (seeded in ensureProfile() from the existing saved profile,
	 * or blank if there was none) and just advances - so skipping a step
	 * during a partial BUTTONS_ONLY re-run keeps that control's previous
	 * mapping instead of unbinding it.
	 */
	private void skipCurrentStep() {
		pendingBinding = null;
		awaitingConfirm = false;
		switch (step) {
			case BUTTON_NOISE_SAMPLE -> goTo(Step.GEAR_DOWN_BUTTON);
			case GEAR_DOWN_BUTTON -> step = Step.GEAR_UP_BUTTON;
			case GEAR_UP_BUTTON -> step = Step.BOOSTER_BUTTON;
			case BOOSTER_BUTTON -> step = Step.SPECIAL_BUTTON;
			case SPECIAL_BUTTON -> step = Step.LEFT_CLICK_BUTTON;
			case LEFT_CLICK_BUTTON -> step = Step.RIGHT_CLICK_BUTTON;
			case RIGHT_CLICK_BUTTON -> step = Step.LOOK_UP_BUTTON;
			case LOOK_UP_BUTTON -> step = Step.LOOK_DOWN_BUTTON;
			case LOOK_DOWN_BUTTON -> {
				computeButtonFields();
				goTo(Step.DONE);
			}
			default -> {
			}
		}
	}

	/**
	 * Prefers whatever device {@link WheelInput} already recognizes as the
	 * calibrated wheel, rather than just SDL's first enumerated device -
	 * with a gamepad/HOTAS/etc. enumerated before the wheel, "first
	 * present" would silently (re-)calibrate the wrong device.
	 */
	private String findFirstDeviceGuid() {
		if (WheelInput.activeGuid != null) {
			return WheelInput.activeGuid;
		}
		int count = SdlJoystickReader.deviceCount();
		return count > 0 ? SdlJoystickReader.deviceGuid(0) : null;
	}

	/**
	 * Called from a device button on the INTRO step (see init()) - overrides
	 * whatever {@link #findFirstDeviceGuid()} auto-picked, so the wizard can
	 * still target a second/different device even after some other one has
	 * already been calibrated once (findFirstDeviceGuid() otherwise always
	 * prefers WheelInput.activeGuid). Clearing profile forces ensureProfile()
	 * to reload it (and the button-mapping working fields) for the newly
	 * selected device on the very next tick.
	 *
	 * Only marks the device as chosen - [다음] is what actually starts the
	 * wizard - so a misclick on the wrong wheel can be corrected before
	 * committing to a whole calibration run. rebuildWidgets() re-runs init() so
	 * the [선택됨] marker moves to the newly picked button.
	 */
	private void selectDevice(String guid) {
		if (guid == null || guid.equalsIgnoreCase(selectedGuid)) return;
		selectedGuid = guid;
		profile = null;
		ensureDeviceSelected();
		ensureProfile();
		rebuildWidgets();
	}

	/**
	 * Picks a device (preferring whatever {@link WheelInput} already
	 * recognizes as the calibrated wheel, rather than just SDL's first
	 * enumerated one - with a gamepad/HOTAS/etc. enumerated before the
	 * wheel, "first present" would silently (re-)calibrate the wrong
	 * device) and opens its SDL handle. Called every tick, not just once:
	 * if SDL hadn't finished enumerating this device the first time this
	 * ran (hotplug-detection lag right as the wizard opened), nothing would
	 * otherwise ever retry it and input would silently never work for the
	 * rest of this calibration session.
	 */
	private void ensureDeviceSelected() {
		if (selectedGuid == null) {
			selectedGuid = findFirstDeviceGuid();
		}
		if (selectedGuid != null) {
			SdlJoystickReader.open(selectedGuid);
		}
	}

	private float[] snapshot() {
		if (!SdlJoystickReader.isOpen()) return new float[0];
		int count = SdlJoystickReader.axisCount();
		float[] out = new float[count];
		for (int i = 0; i < count; i++) out[i] = SdlJoystickReader.axisValue(i);
		return out;
	}

	private void advance() {
		ensureDeviceSelected();

		switch (step) {
			case INTRO -> step = afterIntro();
			case CENTER -> {
				centerSnap = snapshot();
				step = Step.LEFT;
			}
			case LEFT -> {
				leftSnap = snapshot();
				step = Step.RIGHT;
			}
			case RIGHT -> {
				rightSnap = snapshot();
				step = Step.REF90;
			}
			case REF90 -> {
				ref90Snap = snapshot();
				step = Step.LOCK_RANGE_SELECT;
			}
			case THROTTLE_UP -> {
				throttleUpSnap = snapshot();
				step = Step.THROTTLE_DOWN;
			}
			case THROTTLE_DOWN -> {
				throttleDownSnap = snapshot();
				step = Step.BRAKE_UP;
			}
			case BRAKE_UP -> {
				brakeUpSnap = snapshot();
				step = Step.BRAKE_DOWN;
			}
			case BRAKE_DOWN -> {
				brakeDownSnap = snapshot();
				computePedalFields();
				goTo(afterPedalGroup());
			}
			case DONE -> onClose();
			default -> {
			}
		}
	}

	/** Loads the profile already saved for this device (if any) so a partial re-calibration doesn't wipe the rest. */
	private void ensureProfile() {
		if (profile != null || !SdlJoystickReader.isOpen()) return;

		String guid = SdlJoystickReader.openGuid();
		if (guid == null) return;

		// Work on a private copy so a mid-wizard cancel can't leave the
		// live, currently-driving profile (WheelInput.activeProfile is
		// this same cached instance) half-updated - only completeAndSave()
		// at DONE writes the result back via WheelConfig.save().
		WheelProfile existing = WheelConfig.get(guid);
		profile = existing != null ? existing.copy() : new WheelProfile();
		profile.guid = guid;
		profile.name = SdlJoystickReader.openName();

		// Seed the button-mapping working fields from whatever's already
		// saved, so entering BUTTONS_ONLY and skipping every step leaves
		// each binding as it was rather than clearing all 8 to unbound -
		// computeButtonFields() below always writes all 8 from these.
		gearDown = profile.gearDown.copy();
		gearUp = profile.gearUp.copy();
		booster = profile.booster.copy();
		special = profile.special.copy();
		leftClick = profile.leftClick.copy();
		rightClick = profile.rightClick.copy();
		lookUp = profile.lookUp.copy();
		lookDown = profile.lookDown.copy();
	}

	private void computeSteerFields() {
		ensureProfile();
		if (profile == null) return;

		int steerAxis = bestAxis(leftSnap, rightSnap);
		profile.steerAxis = steerAxis;
		applySteerRange(steerAxis, profile);

		WheelTestClient.LOGGER.info("Calibrated steering for {}: axis={}", profile.name, steerAxis);
	}

	/**
	 * Uses the REF90 sample (a raw reading taken at a known, exact 90 degree
	 * turn) to work out how many raw units correspond to one degree of
	 * physical rotation. That lets {@code desiredLockDeg} - picked on the
	 * lock-range screen - be converted back into raw axis values, clamped to
	 * whatever the wheel's actual full-lock readings were (you can't ask for
	 * more range than the hardware physically has).
	 */
	private void applySteerRange(int steerAxis, WheelProfile profile) {
		// centerSnap/ref90Snap are independent snapshots from earlier steps -
		// if the device wasn't connected yet when CENTER or REF90 was
		// advanced past (snapshot() returns an empty array with no device),
		// they can come back shorter than leftSnap/rightSnap even for a
		// steerAxis that bestAxis() validly picked from those two.
		if (steerAxis < 0 || centerSnap == null || steerAxis >= centerSnap.length) {
			profile.steerCenter = 0f;
			profile.steerLeft = -1f;
			profile.steerRight = 1f;
			return;
		}

		float centerRaw = centerSnap[steerAxis];
		float leftRaw = leftSnap[steerAxis];
		float rightRaw = rightSnap[steerAxis];
		profile.steerCenter = centerRaw;
		profile.steerPhysicalLeft = leftRaw;
		profile.steerPhysicalRight = rightRaw;

		float rawPerDegree = (ref90Snap != null && steerAxis < ref90Snap.length)
				? Math.abs(ref90Snap[steerAxis] - centerRaw) / 90f
				: 0f;
		profile.steerRawPerDegree = rawPerDegree;

		if (desiredLockDeg < 0 || rawPerDegree < 1e-6f) {
			// No usable 90-degree reference, or the player chose "full measured lock" - use the raw extremes as-is.
			profile.steerLeft = leftRaw;
			profile.steerRight = rightRaw;
			return;
		}

		float desiredRangeRaw = desiredLockDeg * rawPerDegree;
		float leftFullRangeRaw = Math.abs(leftRaw - centerRaw);
		float rightFullRangeRaw = Math.abs(rightRaw - centerRaw);
		float leftSign = Math.signum(leftRaw - centerRaw);
		float rightSign = Math.signum(rightRaw - centerRaw);

		profile.steerLeft = centerRaw + leftSign * Math.min(desiredRangeRaw, leftFullRangeRaw);
		profile.steerRight = centerRaw + rightSign * Math.min(desiredRangeRaw, rightFullRangeRaw);

		WheelTestClient.LOGGER.info("Steering lock set to {} degrees (raw/deg={}, measured full lock left={} right={})",
				desiredLockDeg, rawPerDegree, leftFullRangeRaw / rawPerDegree, rightFullRangeRaw / rawPerDegree);
	}

	/**
	 * Only overwrites the throttle/brake fields when an axis was actually
	 * detected for that pedal - leaving them untouched (rather than
	 * resetting to -1 / 0 / 1) when detection fails means a failed
	 * PEDALS_ONLY re-run doesn't wipe out a previously-working calibration,
	 * mirroring how the button-mapping steps already treat a skip (see
	 * skipCurrentStep()'s doc comment).
	 */
	private void computePedalFields() {
		ensureProfile();
		if (profile == null) return;

		int throttleAxis = bestAxis(throttleUpSnap, throttleDownSnap, profile.steerAxis);
		if (throttleAxis >= 0) {
			profile.throttleAxis = throttleAxis;
			profile.throttleReleased = throttleUpSnap[throttleAxis];
			profile.throttlePressed = throttleDownSnap[throttleAxis];
		}

		// profile.throttleAxis (not just the local throttleAxis) is excluded too - if
		// throttle detection just above failed (-1), profile.throttleAxis still holds
		// whatever was previously calibrated, and that axis must stay excluded here.
		int brakeAxis = bestAxis(brakeUpSnap, brakeDownSnap, profile.steerAxis, throttleAxis, profile.throttleAxis);
		if (brakeAxis >= 0) {
			profile.brakeAxis = brakeAxis;
			profile.brakeReleased = brakeUpSnap[brakeAxis];
			profile.brakePressed = brakeDownSnap[brakeAxis];
		}

		WheelTestClient.LOGGER.info("Calibrated pedals for {}: throttleAxis={} brakeAxis={}",
				profile.name, throttleAxis, brakeAxis);
	}

	private void computeButtonFields() {
		ensureProfile();
		if (profile == null) return;

		profile.gearDown = gearDown;
		profile.gearUp = gearUp;
		profile.booster = booster;
		profile.special = special;
		profile.leftClick = leftClick;
		profile.rightClick = rightClick;
		profile.lookUp = lookUp;
		profile.lookDown = lookDown;

		WheelTestClient.LOGGER.info("Calibrated buttons for {}", profile.name);
	}

	private void completeAndSave() {
		if (profile == null) return;
		WheelConfig.save(profile);
		WheelTestClient.LOGGER.info("Saved wheel profile for {}", profile.name);
	}

	private int bestAxis(float[] a, float[] b, int... exclude) {
		if (a == null || b == null) return -1;
		int best = -1;
		float bestDiff = 0.15f; // ignore noise/drift below this threshold
		int len = Math.min(a.length, b.length);
		outer:
		for (int i = 0; i < len; i++) {
			for (int ex : exclude) {
				if (ex == i) continue outer;
			}
			float diff = Math.abs(a[i] - b[i]);
			if (diff > bestDiff) {
				bestDiff = diff;
				best = i;
			}
		}
		return best;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);

		g.drawCenteredString(font, title, width / 2, height / 2 - 80, 0xFFFFFF);

		String[] promptLines = promptFor(step).split("\n");
		int promptY = height / 2 - 50;
		for (String line : promptLines) {
			g.drawCenteredString(font, Component.literal(line), width / 2, promptY, 0xFFFF55);
			promptY += 10;
		}
		int infoY = promptY + 10;

		if (step == Step.INTRO) {
			// Device names themselves are the selectable buttons (see init()) -
			// only the nothing-connected case needs its own text line here.
			if (SdlJoystickReader.deviceCount() == 0) {
				g.drawCenteredString(font, Component.literal("연결된 조이스틱이 없습니다"), width / 2, infoY, 0xFF5555);
			}
		} else if (!SdlJoystickReader.isOpen()) {
			g.drawCenteredString(font, Component.literal("연결된 휠 / 조이스틱이 없습니다"), width / 2, infoY, 0xFF5555);
		} else if (isButtonCaptureStep(step)) {
			// A raw "which buttons are currently down" dump becomes
			// unreadable (and useless for finding one specific control) on
			// a device that reports a huge button count, so this shows the
			// most recent *transition* of each kind instead - one line each
			// for button/analog/direction-button - which stays readable no
			// matter how many inputs the device has.
			g.drawCenteredString(font, Component.literal("최근 버튼: " + lastButtonEventText), width / 2, infoY, 0xAAAAAA);
			g.drawCenteredString(font, Component.literal("최근 아날로그: " + lastAxisEventText), width / 2, infoY + 10, 0xAAAAAA);
			g.drawCenteredString(font, Component.literal("최근 방향 버튼: " + lastHatEventText), width / 2, infoY + 20, 0xFFAA55);
			if (awaitingConfirm && pendingBinding != null) {
				g.drawCenteredString(font, Component.literal(describeBinding(pendingBinding) + " 감지됨 | 맞으면 [확인]을 누르세요"),
						width / 2, infoY + 35, 0x55FF55);
			}
		} else if (step == Step.LOCK_RANGE_SELECT) {
			g.drawCenteredString(font, Component.literal(measuredRangeSummary()), width / 2, infoY, 0xAAAAAA);
		} else if (step == Step.BUTTON_NOISE_SAMPLE) {
			long elapsedNanos = noiseSampleStartNanos < 0 ? 0 : System.nanoTime() - noiseSampleStartNanos;
			float remainingSeconds = Math.max(0f, (BUTTON_NOISE_SAMPLE_NANOS - elapsedNanos) / 1_000_000_000f);
			g.drawCenteredString(font, Component.literal(String.format("%.1f초 남음", remainingSeconds)), width / 2, infoY, 0xAAAAAA);
			g.drawCenteredString(font, Component.literal("제외된 버튼: " + noisyButtons.size() + "개, 제외된 아날로그: " + noisyAxes.size() + "개"),
					width / 2, infoY + 10, 0xFFAA55);
		} else {
			// Every axis's live value, one token each - can run to dozens of
			// entries on a device with a huge analog count, so this wraps
			// across as many centered lines as needed instead of joining
			// everything into one line that would run off the screen edges.
			float[] live = snapshot();
			java.util.List<String> tokens = new java.util.ArrayList<>();
			for (int i = 0; i < live.length; i++) {
				tokens.add((i + 1) + "번 아날로그: " + String.format("%.2f", live[i]));
			}
			drawWrappedTokens(g, tokens, infoY, 0xAAAAAA);
		}
	}

	/**
	 * Packs {@code tokens} left-to-right into centered lines, starting a new
	 * line before one would grow wider than the screen - used wherever the
	 * number of items scales with the device's own button/axis count and so
	 * can't be assumed to fit on one line.
	 */
	private void drawWrappedTokens(GuiGraphics g, java.util.List<String> tokens, int startY, int color) {
		int maxWidth = Math.max(120, width - 20);
		int lineHeight = 10;
		StringBuilder line = new StringBuilder();
		int y = startY;
		for (String token : tokens) {
			String candidate = line.length() == 0 ? token : line + "  " + token;
			if (line.length() > 0 && font.width(candidate) > maxWidth) {
				g.drawCenteredString(font, Component.literal(line.toString()), width / 2, y, color);
				y += lineHeight;
				line = new StringBuilder(token);
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (line.length() > 0) {
			g.drawCenteredString(font, Component.literal(line.toString()), width / 2, y, color);
		}
	}

	private String measuredRangeSummary() {
		int steerAxis = bestAxis(leftSnap, rightSnap);
		if (steerAxis < 0 || centerSnap == null || ref90Snap == null
				|| steerAxis >= centerSnap.length || steerAxis >= ref90Snap.length) {
			return "";
		}

		float centerRaw = centerSnap[steerAxis];
		float rawPerDegree = Math.abs(ref90Snap[steerAxis] - centerRaw) / 90f;
		if (rawPerDegree < 1e-6f) return "90도 기준점 인식 실패. 실측 풀 락 그대로를 선택하세요";

		float leftDeg = Math.abs(leftSnap[steerAxis] - centerRaw) / rawPerDegree;
		float rightDeg = Math.abs(rightSnap[steerAxis] - centerRaw) / rawPerDegree;
		return String.format("측정된 풀 락 회전범위: 좌 %.0f도 / 우 %.0f도", leftDeg, rightDeg);
	}

	private String promptFor(Step s) {
		return switch (s) {
			case INTRO -> "보정할 장치를 선택하고 [다음]을 누르세요";
			case CENTER -> "휠을 정중앙에 두고 [다음]을 누르세요";
			case LEFT -> "휠을 왼쪽 끝까지 돌리고 [다음]을 누르세요";
			case RIGHT -> "휠을 오른쪽 끝까지 돌리고 [다음]을 누르세요";
			case REF90 -> "휠을 왼쪽으로 정확히 90도만 돌리고 [다음]을 누르세요";
			case LOCK_RANGE_SELECT -> "원하는 조향 범위를 선택하세요";

			case THROTTLE_UP -> "가속 페달에서 발을 떼고 [다음]을 누르세요";
			case THROTTLE_DOWN -> "가속 페달을 끝까지 밟은 채로 [다음]을 누르세요";
			case BRAKE_UP -> "브레이크 페달에서 발을 떼고 [다음]을 누르세요";
			case BRAKE_DOWN -> "브레이크 페달을 끝까지 밟은 채로 [다음]을 누르세요";

			case BUTTON_NOISE_SAMPLE -> "아무것도 만지지 말고 잠시 기다려주세요\n(자꾸 흔들리는 아날로그/버튼을 미리 걸러내는 중입니다)";

			case GEAR_DOWN_BUTTON -> "기어 다운으로 쓸 버튼을 꾹 누르세요\n플레이어의 왼쪽 이동에 매핑됩니다";
			case GEAR_UP_BUTTON -> "기어 업으로 쓸 버튼을 꾹 누르세요\n플레이어의 오른쪽 이동에 매핑됩니다";

			case BOOSTER_BUTTON -> "부스터로 쓸 버튼(또는 남는 페달)을 꾹 누르세요\n기어 다운과 같은 왼쪽 이동에 매핑됩니다";
			case SPECIAL_BUTTON -> "익시드/차저/ERS/점프로 쓸 버튼(또는 남는 페달)을 꾹 누르세요\n플레이어의 점프에 매핑됩니다";
			case LEFT_CLICK_BUTTON -> "좌클릭으로 쓸 버튼(또는 남는 페달)을 꾹 누르세요\n멀티 플레이에서 방장을 넘길 때 사용합니다";
			case RIGHT_CLICK_BUTTON -> "우클릭으로 쓸 버튼(또는 남는 페달)을 꾹 누르세요\n인게임에서 버튼을 누를 때 사용합니다";

			case LOOK_UP_BUTTON -> "시선 위로 쓸 버튼(또는 남는 페달)을 꾹 누르세요\n시선이 돌아갔을 때 레이스 중에도 조정할 수 있습니다";
			case LOOK_DOWN_BUTTON -> "시선 아래로 쓸 버튼(또는 남는 페달)을 꾹 누르세요\n시선이 돌아갔을 때 레이스 중에도 조정할 수 있습니다";

			case DONE -> "설정 완료! [다음]을 눌러 닫으세요";
		};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
