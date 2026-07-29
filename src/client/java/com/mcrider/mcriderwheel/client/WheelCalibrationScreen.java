package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// 장치 모델을 하드코딩하지 않고, 플레이어가 각 동작(중앙, 완전 좌/우, 페달)을 수행하면
// 어느 축이 얼마나 움직였는지 직접 알아내는 보정 위저드.
// Scope로 휠/페달/버튼 중 일부만 다시 보정하고 나머지는 그대로 둘 수 있다
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
		RIGHT_CLICK_BUTTON, LOOK_UP_BUTTON, LOOK_DOWN_BUTTON, CROUCH_BUTTON, SWAP_HANDS_BUTTON, VIEW_TOGGLE_BUTTON,
		HOTBAR_SHIFT_BUTTON, DONE
	}

	// 기준선에서 이 정도는 움직여야 의도적인 입력으로 인정 (드리프트/노이즈 필터링)
	private static final float AXIS_CAPTURE_DELTA = 0.3f;

	// 락 범위 선택 화면에 제시하는 각도, -1은 "실측 풀 락 그대로"
	private static final float[] LOCK_RANGE_PRESETS = {90f, 180f, 270f, 450f, 900f, -1f};

	// axisBaseline이 "정지 상태"로 판단하는 틱 간 허용 흔들림
	private static final float CAPTURE_JITTER_BAND = 0.05f;

	// "최근 아날로그" 진단 표시 전용 임계값 - 제외된 축이라도 물리적으로 움직이면 보여야 하므로
	// AXIS_CAPTURE_DELTA/isExcludedAxis와는 별개로 둔다
	private static final float AXIS_DIAGNOSTIC_DELTA = 0.05f;

	// 위저드가 열리자마자 드라이버/가상조이스틱 계층의 시동 잡음이 섞일 수 있어서
	// 이 시간 동안 손을 떼게 하고, 그 사이 움직인 버튼/축은 영구 제외한다
	private static final long BUTTON_NOISE_SAMPLE_NANOS = 10_000_000_000L; // 10초

	private static final int[] HAT_DIRECTION_BITS = {
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_UP,
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_RIGHT,
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_DOWN,
			io.github.libsdl4j.api.joystick.SdlJoystickConst.SDL_HAT_LEFT,
	};
	private static final String[] HAT_DIRECTION_NAMES = {"위", "오른쪽", "아래", "왼쪽"};

	// HAT 비트마스크에 대한 한국어 표기 (단일 방향 또는 대각선 조합)
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
	// 설정 화면이 아니라 WheelClientMain의 접속/핫플러그 안내로 열렸는지 - onClose()에서 사용
	private final boolean autoTriggered;
	// 위저드가 실제로 DONE까지 도달했는지 - 중간 이탈(ESC, 나가기)과 구분하기 위함
	private boolean reachedDone;
	private Step step = Step.INTRO;
	private String selectedGuid;

	private float[] centerSnap;
	private float[] leftSnap;
	private float[] rightSnap;
	private float[] ref90Snap;
	private float desiredLockDeg = -1f; // -1 = 실측 풀 락 그대로
	private float[] throttleUpSnap;
	private float[] throttleDownSnap;
	private float[] brakeUpSnap;
	private float[] brakeDownSnap;
	// 조향축 인식 실패 시 남기는 경고 배너 텍스트 (null = 없음).
	// 기존에 잘 작동하던 보정을 지켰는지, 아예 처음부터 조향 불가능했는지에 따라 문구가 다르다
	private String steerCalibrationWarning;

	private WheelProfile profile;
	private float[] axisBaseline;
	// 마지막으로 정지 상태였던 이후 각 축의 최대 편차(및 그때의 raw 값) - 순간값이 아니라
	// 최댓값을 잡아야 눌렀다 뗀 페달의 진짜 최대 이동량을 놓치지 않는다
	private float[] axisPeakDeviation;
	private float[] axisPeakValue;
	private final java.util.Set<Integer> claimedAxes = new java.util.HashSet<>();
	// BUTTON_NOISE_SAMPLE 동안 한 번이라도 움직인 인덱스 - 이후 다시는 후보로 고려하지 않는다
	private final java.util.Set<Integer> noisyButtons = new java.util.HashSet<>();
	private final java.util.Set<Integer> noisyAxes = new java.util.HashSet<>();
	private long noiseSampleStartNanos = -1;
	// 버튼/HAT 캡처용 엣지 감지 상태 - 버튼 매핑 단계 전체에 걸쳐 한 번만 초기화된다
	private boolean[] prevCaptureButtonsDown;
	private byte[] prevCaptureHats;
	// 후보가 감지되는 즉시 설정되고, 확인 대기 중에 다른 입력이 들어오면 그걸로 교체된다.
	// 누르고 뗀 다음 따로 [확인]을 눌러도 되고, 계속 누르고 있을 필요는 없다
	private InputBinding pendingBinding;
	private boolean awaitingConfirm;
	// 이번 캡처 기회(새 단계, 또는 버튼/HAT 후보 대기 시작)가 열렸을 때 이미 서 있던
	// 축별 최대 편차 - checkInputCapture()에서 이 값 위로 더 움직여야 새 입력으로 인정된다
	private float[] axisOverrideFloor;
	private Step axisFloorStep;

	// 인식 안 된 입력이 실제로 어떤 물리 컨트롤인지 알아보기 위한 원시 진단용 - 표시 전용
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
	private InputBinding crouch = new InputBinding();
	private InputBinding swapHands = new InputBinding();
	private InputBinding viewToggle = new InputBinding();
	private InputBinding hotbarShift = new InputBinding();

	private Button nextButton;
	private Button skipButton;
	private Button exitButton;
	private final Button[] lockRangeButtons = new Button[LOCK_RANGE_PRESETS.length];
	// INTRO 단계에서 장치를 직접 고를 수 있게 하는 버튼들 - selectDevice() 참고
	private final java.util.List<Button> deviceButtons = new java.util.ArrayList<>();
	// 장치가 비정상적으로 많이 열거되는 환경에서 목록이 무한정 길어지지 않도록 상한을 둔다
	private static final int MAX_SELECTABLE_DEVICE_BUTTONS = 6;
	// INTRO 단계에서 [다음]/[나가기]가 시작되는 Y - 장치 목록 길이에 따라 달라진다
	private int introNextY;
	private int hiddenDeviceCount;
	private int deviceListBottom;

	// WheelClientMain의 자동 보정 안내로 열렸을 때 (설정 화면에서 연 게 아님)
	public WheelCalibrationScreen() {
		this(Scope.FULL, null, true);
	}

	public WheelCalibrationScreen(Scope scope, Screen parent) {
		this(scope, parent, false);
	}

	private WheelCalibrationScreen(Scope scope, Screen parent, boolean autoTriggered) {
		super(Component.literal("마크라이더 레이싱 휠 보정"));
		this.scope = scope;
		this.parent = parent;
		this.autoTriggered = autoTriggered;
	}

	@Override
	protected void init() {
		ensureDeviceSelected();
		ensureProfile(); // 버튼 캡처가 조향/페달 축을 제외할 수 있도록 미리 로드

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

		// Y는 updateWidgetVisibility()가 매 틱 다시 잡는다
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

		// init()은 창 크기 변경 시마다 다시 실행되므로 목록도 매번 비워야 orphan 위젯이 안 쌓인다
		deviceButtons.clear();
		int introPromptLines = promptFor(Step.INTRO).split("\n").length;
		int devBtnWidth = Math.min(280, width - 20);
		int devBtnHeight = 20;
		int devBtnGap = 4;
		int devStartY = (height / 2 - 50) + introPromptLines * 10 + 10;
		int navStackHeight = devBtnHeight * 2 + 5;
		int roomForButtons = Math.max(0, (height - 8 - navStackHeight - devStartY) / (devBtnHeight + devBtnGap));
		int totalDevices = SdlJoystickReader.deviceCount();
		int selectableDevices = Math.min(Math.min(totalDevices, MAX_SELECTABLE_DEVICE_BUTTONS), roomForButtons);
		int placed = 0;
		for (int i = 0; i < totalDevices && placed < selectableDevices; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null) continue;
			String name = SdlJoystickReader.deviceName(i);
			String label = (name.isEmpty() ? ("장치 " + (i + 1)) : name)
					+ (guid.equalsIgnoreCase(selectedGuid) ? " [선택됨]" : "");
			Button btn = Button.builder(Component.literal(label), b -> selectDevice(guid))
					.bounds(width / 2 - devBtnWidth / 2, devStartY + placed * (devBtnHeight + devBtnGap), devBtnWidth, devBtnHeight)
					.build();
			deviceButtons.add(btn);
			addRenderableWidget(btn);
			placed++;
		}
		hiddenDeviceCount = Math.max(0, totalDevices - placed);
		deviceListBottom = devStartY + placed * (devBtnHeight + devBtnGap);
		introNextY = Math.max(height / 2 + 40, deviceListBottom + (hiddenDeviceCount > 0 ? 22 : 8));

		updateWidgetVisibility();
	}

	@Override
	public void onClose() {
		if (autoTriggered && !reachedDone && selectedGuid != null) {
			// 자동으로 열린 위저드를 완료하지 않고 나갔으므로, 이 장치는 매번 다시
			// 안내되지 않게 표시한다. 설정 화면에서 나중에 직접 보정하는 것과는 무관하다
			WheelConfig.declineAutoCalibration(selectedGuid);
		}
		if (parent != null) {
			minecraft.setScreen(parent);
		} else {
			super.onClose();
		}
	}

	@Override
	public void tick() {
		super.tick();
		// 전체를 감싼다 - 아래 로직이 장치별 축/버튼 개수로 배열 인덱싱을 많이 하는데
		// 위저드 도중 장치 형태가 바뀌는 경우(핫플러그 등) 예외가 나도 화면이 죽지 않게 한다
		try {
			updateWidgetVisibility();
			ensureDeviceSelected();
			SdlJoystickReader.update();
			ensureProfile();
			if (step == Step.BUTTON_NOISE_SAMPLE) {
				runNoiseSample();
			} else {
				checkInputCapture();
			}
		} catch (Throwable t) {
			WheelClientMain.LOGGER.error("[MCRiderWheel] calibration screen tick failed - continuing", t);
		}
	}

	private void updateWidgetVisibility() {
		boolean skippable = isButtonCaptureStep(step) || step == Step.BUTTON_NOISE_SAMPLE;
		skipButton.visible = skippable;
		skipButton.active = skippable;
		int navTop = step == Step.INTRO ? introNextY : height / 2 + 40;
		nextButton.setY(navTop);
		skipButton.setY(navTop + 25);
		exitButton.setY(navTop + (skippable ? 50 : 25));

		// 자동 감지/타이머로 진행되는 단계에서는 [다음]이 의미가 없다. 단, awaitingConfirm은
		// 방금 감지된 후보를 확인하는 용도라 이때는 [확인]으로 라벨을 바꿔 다시 보여준다
		boolean waitingForOtherInput = isButtonCaptureStep(step) || step == Step.LOCK_RANGE_SELECT
				|| step == Step.BUTTON_NOISE_SAMPLE;
		nextButton.visible = !waitingForOtherInput || awaitingConfirm;
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

	// --- scope별 단계 순서 -------------------------------------------------

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

	// DONE으로 가는 모든 전환이 여기를 거치게 해서 저장이 정확히 한 번만 일어나게 한다
	private void goTo(Step next) {
		if (next == Step.DONE) {
			completeAndSave();
			reachedDone = true;
		}
		step = next;
	}

	private boolean isButtonCaptureStep(Step s) {
		return s == Step.GEAR_DOWN_BUTTON || s == Step.GEAR_UP_BUTTON || s == Step.BOOSTER_BUTTON
				|| s == Step.SPECIAL_BUTTON || s == Step.LEFT_CLICK_BUTTON || s == Step.RIGHT_CLICK_BUTTON
				|| s == Step.LOOK_UP_BUTTON || s == Step.LOOK_DOWN_BUTTON || s == Step.CROUCH_BUTTON
				|| s == Step.SWAP_HANDS_BUTTON || s == Step.VIEW_TOGGLE_BUTTON || s == Step.HOTBAR_SHIFT_BUTTON;
	}

	// 버튼 매핑 단계 전체에서 딱 한 번만 시드한다 - 페달이 복귀 중일 때 다시 시드하면
	// 그 움직임 자체가 새 입력으로 오인되어 나머지 단계를 연쇄적으로 채워버릴 수 있다
	private void captureAxisBaselineOnce() {
		if (axisBaseline != null) return;
		axisBaseline = snapshot();
	}

	// 조향/페달로 이미 쓰였거나, 앞선 단계에서 이미 배정됐거나, 노이즈로 표시된 축은 제외한다
	private boolean isExcludedAxis(int axis) {
		if (claimedAxes.contains(axis) || noisyAxes.contains(axis)) return true;
		return profile != null && (axis == profile.steerAxis || axis == profile.throttleAxis || axis == profile.brakeAxis);
	}

	// 실제 캡처 로직과 별개로, 화면에 뭔가 반응하고 있음을 보여주기 위한 원시 이벤트 표시
	private void updateRawEventDiagnostic() {
		if (!SdlJoystickReader.isOpen()) return;

		int buttons = SdlJoystickReader.buttonCount();
		if (prevButtonsDown == null || prevButtonsDown.length != buttons) {
			prevButtonsDown = new boolean[buttons];
		}
		for (int i = 0; i < buttons; i++) {
			boolean down = SdlJoystickReader.buttonDown(i);
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

	// BUTTON_NOISE_SAMPLE_NANOS 동안 손을 뗀 채로 두게 하고, 그 사이 조금이라도
	// 움직인 버튼/축은 이후 어떻게 안정되든 영구 제외한다
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

	// 노이즈가 아닌 버튼/HAT/축 중 이번 틱에 처음 "눌림"으로 전환된 것을 후보로 잡고
	// [확인]으로 최종 확정한다 - 계속 누르고 있을 필요는 없다
	private void checkInputCapture() {
		if (!isButtonCaptureStep(step) || !SdlJoystickReader.isOpen()) return;
		captureAxisBaselineOnce();
		updateRawEventDiagnostic();

		// 단계가 바뀔 때마다 한 번만 floor를 다시 잡는다 - 안 그러면 스프링이 없는
		// 로터리 다이얼 등이 이전 단계에서 남긴 편차로 새 단계 첫 틱에 바로 당첨돼버린다
		if (axisFloorStep != step) {
			snapshotAxisOverrideFloor();
			axisFloorStep = step;
		}

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

		// 축: 기준선에서 가장 많이 벗어난 축을 고른다 (제일 먼저 임계값을 넘은 게 아니라).
		// axisBaseline은 계속 살아있는 값이라, 정지 상태(jitter band 이내)인 축은
		// 기준선을 현재 값으로 계속 갱신해서 실제로 움직인 축만 후보가 될 수 있게 한다.
		// 후보 값은 순간값이 아니라 정지 상태 이후의 최대 편차값을 쓴다
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
					axisPeakDeviation[i] = 0f; // 정지 상태로 복귀 - 다음 진짜 입력을 위해 초기화
				} else if (deviation > axisPeakDeviation[i]) {
					axisPeakDeviation[i] = deviation;
					axisPeakValue[i] = current;
				}
			}
		}

		// floor가 잡힌 이후 새로 생긴 움직임만 후보가 될 수 있다 - 스프링 없는 축이
		// 단계 시작 전부터 이미 치우쳐 있어도 아무 입력 없이 새 단계를 당첨시키지 못하게 한다
		int axisCandidate = -1;
		float axisCandidateValue = 0f;
		if (axisPeakDeviation != null) {
			float bestDeviation = AXIS_CAPTURE_DELTA;
			int count = Math.min(SdlJoystickReader.axisCount(), axisPeakDeviation.length);
			for (int i = 0; i < count; i++) {
				if (isExcludedAxis(i)) continue;
				float floor = axisOverrideFloor != null && i < axisOverrideFloor.length ? axisOverrideFloor[i] : 0f;
				if (axisPeakDeviation[i] <= floor + AXIS_CAPTURE_DELTA) continue;
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
			// 확정되기 전엔 claimedAxes에 안 넣는다 - 감지만 되고 스킵/교체되면 영구 제외되면 안 된다
			pendingBinding = binding;
			awaitingConfirm = true;
		}
	}

	// 버튼/HAT 후보가 잡힌 시점의 축별 최대 편차를 기록해서, 이후 축 입력은 그 시점
	// 이후로 얼마나 움직였는지로 판단하게 한다
	private void snapshotAxisOverrideFloor() {
		if (axisPeakDeviation == null) {
			axisOverrideFloor = null;
			return;
		}
		axisOverrideFloor = axisPeakDeviation.clone();
	}

	// 대기 중인 후보를 [확인]으로 최종 확정한다
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

	private String describeBinding(InputBinding b) {
		if (b.buttonIndex >= 0) return (b.buttonIndex + 1) + "번 버튼";
		if (b.hatIndex >= 0) return (b.hatIndex + 1) + "번 방향 버튼 (" + hatDirectionName(b.hatDirection) + ")";
		if (b.axisIndex >= 0) return (b.axisIndex + 1) + "번 아날로그";
		return "";
	}

	private static String buttonFieldName(Step s) {
		return switch (s) {
			case GEAR_DOWN_BUTTON -> "기어 다운";
			case GEAR_UP_BUTTON -> "기어 업";
			case BOOSTER_BUTTON -> "부스터";
			case SPECIAL_BUTTON -> "익시드/차저/ERS/점프";
			case LEFT_CLICK_BUTTON -> "좌클릭";
			case RIGHT_CLICK_BUTTON -> "우클릭";
			case LOOK_UP_BUTTON -> "시선 위";
			case LOOK_DOWN_BUTTON -> "시선 아래";
			case CROUCH_BUTTON -> "웅크리기";
			case SWAP_HANDS_BUTTON -> "양손 바꾸기";
			case VIEW_TOGGLE_BUTTON -> "시점 전환";
			case HOTBAR_SHIFT_BUTTON -> "핫바 시프트";
			default -> null;
		};
	}

	// 같은 물리 컨트롤인지 비교 (미배정(-1)은 서로 절대 매칭되지 않는다)
	private static boolean sameControl(InputBinding a, InputBinding b) {
		if (a.buttonIndex >= 0) return a.buttonIndex == b.buttonIndex;
		if (a.hatIndex >= 0) return a.hatIndex == b.hatIndex && a.hatDirection == b.hatDirection;
		if (a.axisIndex >= 0) return a.axisIndex == b.axisIndex;
		return false;
	}

	// candidate가 이미 다른 기능에 배정돼 있는지 - 이번 세션에서 아직 안 지나간 단계의
	// 필드도 저장된 값을 그대로 갖고 있으므로 전부 검사한다 (현재 단계 자신은 제외)
	private String findDuplicateFieldName(InputBinding candidate) {
		String currentField = buttonFieldName(step);
		record Slot(String name, InputBinding binding) {
		}
		Slot[] slots = {
				new Slot("기어 다운", gearDown), new Slot("기어 업", gearUp), new Slot("부스터", booster),
				new Slot("익시드/차저/ERS/점프", special), new Slot("좌클릭", leftClick), new Slot("우클릭", rightClick),
				new Slot("시선 위", lookUp), new Slot("시선 아래", lookDown), new Slot("웅크리기", crouch),
				new Slot("양손 바꾸기", swapHands), new Slot("시점 전환", viewToggle), new Slot("핫바 시프트", hotbarShift),
		};
		for (Slot slot : slots) {
			if (slot.name.equals(currentField)) continue;
			if (sameControl(candidate, slot.binding)) return slot.name;
		}
		return null;
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
				step = Step.CROUCH_BUTTON;
			}
			case CROUCH_BUTTON -> {
				crouch = binding;
				step = Step.SWAP_HANDS_BUTTON;
			}
			case SWAP_HANDS_BUTTON -> {
				swapHands = binding;
				step = Step.VIEW_TOGGLE_BUTTON;
			}
			case VIEW_TOGGLE_BUTTON -> {
				viewToggle = binding;
				step = Step.HOTBAR_SHIFT_BUTTON;
			}
			case HOTBAR_SHIFT_BUTTON -> {
				hotbarShift = binding;
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
		// 위저드 끝이 아니라 이 그룹이 끝나는 즉시 저장한다 - 안 그러면 휠만 보정하고
		// 버튼은 안 끝낸 채로 나갈 때 아무것도 저장되지 않는다
		persistProgress();
		goTo(afterWheelGroup());
	}

	// 이 단계의 필드에 이미 있던 값(ensureProfile()이 저장된 프로필에서 시드해둠)을
	// 그대로 두고 넘어간다 - BUTTONS_ONLY 재실행 중 건너뛴 컨트롤이 매핑 해제되지 않게 한다
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
			case LOOK_DOWN_BUTTON -> step = Step.CROUCH_BUTTON;
			case CROUCH_BUTTON -> step = Step.SWAP_HANDS_BUTTON;
			case SWAP_HANDS_BUTTON -> step = Step.VIEW_TOGGLE_BUTTON;
			case VIEW_TOGGLE_BUTTON -> step = Step.HOTBAR_SHIFT_BUTTON;
			case HOTBAR_SHIFT_BUTTON -> {
				computeButtonFields();
				goTo(Step.DONE);
			}
			default -> {
			}
		}
	}

	// WheelInput이 이미 인식하는 계산된 휠을 우선한다 - 안 그러면 게임패드가 먼저
	// 열거될 때 엉뚱한 장치를 (재)보정하게 된다
	private String findFirstDeviceGuid() {
		if (WheelInput.activeGuid != null) {
			return WheelInput.activeGuid;
		}
		int count = SdlJoystickReader.deviceCount();
		return count > 0 ? SdlJoystickReader.deviceGuid(0) : null;
	}

	// INTRO 단계의 장치 버튼에서 호출됨 - findFirstDeviceGuid()의 자동 선택을 덮어써서
	// 이미 보정된 장치가 있어도 다른 장치를 고를 수 있게 한다. [다음]을 눌러야 실제로 시작된다
	private void selectDevice(String guid) {
		if (guid == null || guid.equalsIgnoreCase(selectedGuid)) return;
		selectedGuid = guid;
		profile = null;
		ensureDeviceSelected();
		ensureProfile();
		rebuildWidgets();
	}

	// 장치를 고르고(WheelInput이 이미 인식하는 걸 우선) SDL 핸들을 연다.
	// 매 틱 재시도해야 위저드가 열리자마자의 열거 지연도 커버된다
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
			case INTRO -> {
				// INTRO를 넘어간다는 건 이 장치를 지금 보정하겠다는 확실한 의사표시다.
				// 예전에 중단된 위저드의 거부 표시를 여기서 지워서, 지금 실제로
				// 작업 중인 장치가 계속 자동 안내 대상에서 빠져있지 않게 한다
				WheelConfig.clearAutoCalibrationDecline(selectedGuid);
				step = afterIntro();
			}
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
				persistProgress();
				goTo(afterPedalGroup());
			}
			case DONE -> onClose();
			default -> {
			}
		}
	}

	// 이 장치에 이미 저장된 프로필을 불러와서, 부분 재보정이 나머지를 지우지 않게 한다
	private void ensureProfile() {
		if (!SdlJoystickReader.isOpen()) return;

		String guid = SdlJoystickReader.openGuid();
		if (guid == null) return;
		// "profile != null"만으로는 부족하다 - selectDevice()/open()이 핫플러그와
		// 경합하면 리더 핸들이 한두 틱 동안 이전 장치를 가리킬 수 있다
		if (profile != null && guid.equalsIgnoreCase(profile.guid)) return;

		// 별도 복사본에서 작업한다 - 위저드 중단이 현재 주행 중인 프로필을 반쯤 건드리면 안 된다
		WheelProfile existing = WheelConfig.get(guid);
		profile = existing != null ? existing.copy() : new WheelProfile();
		profile.guid = guid;
		profile.name = SdlJoystickReader.openName();

		// 버튼 매핑 작업 필드도 기존 저장값으로 시드해서, 전부 건너뛰면 그대로 유지되게 한다
		gearDown = profile.gearDown.copy();
		gearUp = profile.gearUp.copy();
		booster = profile.booster.copy();
		special = profile.special.copy();
		leftClick = profile.leftClick.copy();
		rightClick = profile.rightClick.copy();
		lookUp = profile.lookUp.copy();
		lookDown = profile.lookDown.copy();
		crouch = profile.crouch.copy();
		swapHands = profile.swapHands.copy();
		viewToggle = profile.viewToggle.copy();
		hotbarShift = profile.hotbarShift.copy();
	}

	private void computeSteerFields() {
		ensureProfile();
		if (profile == null) return;

		int steerAxis = bestAxis(leftSnap, rightSnap);
		if (steerAxis < 0) {
			// 기존에 잘 작동하던 조향 설정이 있으면 그대로 두고, 없으면 계속 조향 불가능 상태로 둔다
			boolean keptWorkingCalibration = profile.isDriveable();
			steerCalibrationWarning = keptWorkingCalibration
					? "조향축 인식 실패 - 기존 조향 설정을 유지합니다"
					: "조향축 인식 실패 - 이 휠은 아직 조향할 수 없습니다. 휠 보정을 다시 실행하세요";
			WheelClientMain.LOGGER.warn("Steering calibration failed for {} (wheel not turned far enough between LEFT/RIGHT) - {}",
					profile.name, keptWorkingCalibration ? "keeping previously saved steering" : "device still has no usable steering");
			return;
		}

		steerCalibrationWarning = null;
		profile.steerAxis = steerAxis;
		applySteerRange(steerAxis, profile);

		WheelClientMain.LOGGER.info("Calibrated steering for {}: axis={}", profile.name, steerAxis);
	}

	// REF90에서 측정한 정확히 90도 회전 값으로 raw 단위당 각도를 구하고, 그걸로
	// 락 범위 선택 값을 raw 축 값으로 환산한다 (물리 범위를 넘어서는 못 잡는다)
	private void applySteerRange(int steerAxis, WheelProfile profile) {
		// centerSnap/ref90Snap은 각자 독립적으로 찍힌 스냅샷이라, 그 단계 때 장치가
		// 아직 연결 안 됐으면 leftSnap/rightSnap보다 짧을 수 있다
		if (steerAxis < 0 || centerSnap == null || steerAxis >= centerSnap.length) {
			profile.steerCenter = 0f;
			profile.steerLeft = -1f;
			profile.steerRight = 1f;
			profile.steerPhysicalLeft = -1f;
			profile.steerPhysicalRight = 1f;
			profile.steerRawPerDegree = 0f;
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
			// 90도 기준점이 없거나 "실측 풀 락 그대로"를 선택한 경우 - 실측 극값을 그대로 쓴다
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

		WheelClientMain.LOGGER.info("Steering lock set to {} degrees (raw/deg={}, measured full lock left={} right={})",
				desiredLockDeg, rawPerDegree, leftFullRangeRaw / rawPerDegree, rightFullRangeRaw / rawPerDegree);
	}

	// 페달 축이 실제로 감지됐을 때만 필드를 덮어쓴다 - 실패한 PEDALS_ONLY 재실행이
	// 기존에 잘 작동하던 보정을 지우지 않게 한다
	private void computePedalFields() {
		ensureProfile();
		if (profile == null) return;

		int throttleAxis = bestAxis(throttleUpSnap, throttleDownSnap, profile.steerAxis);
		if (throttleAxis >= 0) {
			profile.throttleAxis = throttleAxis;
			profile.throttleReleased = throttleUpSnap[throttleAxis];
			profile.throttlePressed = throttleDownSnap[throttleAxis];
		}

		int brakeAxis = bestAxis(brakeUpSnap, brakeDownSnap, profile.steerAxis, throttleAxis, profile.throttleAxis);
		if (brakeAxis >= 0) {
			profile.brakeAxis = brakeAxis;
			profile.brakeReleased = brakeUpSnap[brakeAxis];
			profile.brakePressed = brakeDownSnap[brakeAxis];
		}

		WheelClientMain.LOGGER.info("Calibrated pedals for {}: throttleAxis={} brakeAxis={}",
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
		profile.crouch = crouch;
		profile.swapHands = swapHands;
		profile.viewToggle = viewToggle;
		profile.hotbarShift = hotbarShift;

		WheelClientMain.LOGGER.info("Calibrated buttons for {}", profile.name);
	}

	private void completeAndSave() {
		if (profile == null) return;
		WheelConfig.save(profile);
		// 방금 (재)보정한 장치를 선호 장치로 설정한다 - 명시적으로 고른 적 없어도
		// 방금 작업한 게 지금 몰고 싶은 휠일 가능성이 가장 크다
		WheelConfig.setPreferredGuid(profile.guid);
		WheelClientMain.LOGGER.info("Saved wheel profile for {}", profile.name);
	}

	// 위저드를 끝내지 않고 지금까지 보정된 내용만 저장한다 - 한 그룹(휠/페달)이
	// 끝날 때마다 호출돼서 도중에 나가도 이미 끝난 그룹은 잃지 않는다
	private void persistProgress() {
		if (profile == null) return;
		WheelConfig.save(profile);
		WheelConfig.setPreferredGuid(profile.guid);
	}

	private int bestAxis(float[] a, float[] b, int... exclude) {
		if (a == null || b == null) return -1;
		int best = -1;
		float bestDiff = 0.15f; // 노이즈/드리프트 이하는 무시
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

		if (steerCalibrationWarning != null) {
			g.drawCenteredString(font, Component.literal(steerCalibrationWarning), width / 2, infoY, 0xFF5555);
			infoY += 10;
		}

		if (step == Step.INTRO) {
			if (SdlJoystickReader.deviceCount() == 0) {
				g.drawCenteredString(font, Component.literal("연결된 조이스틱이 없습니다"), width / 2, infoY, 0xFF5555);
			} else if (hiddenDeviceCount > 0) {
				g.drawCenteredString(font, Component.literal("(" + hiddenDeviceCount + "개 더 연결되어 있지만 목록에 표시할 공간이 없습니다)"),
						width / 2, deviceListBottom + 4, 0xFFAA55);
			}
		} else if (!SdlJoystickReader.isOpen()) {
			g.drawCenteredString(font, Component.literal("연결된 휠 / 조이스틱이 없습니다"), width / 2, infoY, 0xFF5555);
		} else if (isButtonCaptureStep(step)) {
			// 버튼 수가 많은 장치에서 전체 상태를 다 보여주면 못 알아보므로,
			// 종류별 최근 전환 한 줄씩만 보여준다
			g.drawCenteredString(font, Component.literal("최근 버튼: " + lastButtonEventText), width / 2, infoY, 0xAAAAAA);
			g.drawCenteredString(font, Component.literal("최근 아날로그: " + lastAxisEventText), width / 2, infoY + 10, 0xAAAAAA);
			g.drawCenteredString(font, Component.literal("최근 방향 버튼: " + lastHatEventText), width / 2, infoY + 20, 0xFFAA55);
			if (awaitingConfirm && pendingBinding != null) {
				String duplicateField = findDuplicateFieldName(pendingBinding);
				String suffix = duplicateField != null ? " | 이미 [" + duplicateField + "]에 배정됨" : "";
				g.drawCenteredString(font, Component.literal(describeBinding(pendingBinding) + " 감지됨" + suffix + " | 맞으면 [확인]을 누르세요"),
						width / 2, infoY + 35, duplicateField != null ? 0xFFAA55 : 0x55FF55);
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
			// 축이 많은 장치에서 한 줄에 다 안 들어가므로 여러 줄로 감싸서 보여준다
			float[] live = snapshot();
			java.util.List<String> tokens = new java.util.ArrayList<>();
			for (int i = 0; i < live.length; i++) {
				tokens.add((i + 1) + "번 아날로그: " + String.format("%.2f", live[i]));
			}
			drawWrappedTokens(g, tokens, infoY, 0xAAAAAA);
		}
	}

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
		if (steerAxis < 0) {
			return "조향축 인식 실패 - 휠을 왼쪽/오른쪽 끝까지 충분히 돌렸는지 확인하세요";
		}
		if (centerSnap == null || ref90Snap == null
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

			case BUTTON_NOISE_SAMPLE -> "아무것도 만지지 말고 잠시 기다려주세요\n(자주 흔들리는 아날로그/버튼을 미리 걸러내는 중입니다)";

			case GEAR_DOWN_BUTTON -> "기어 다운으로 쓸 버튼을 누르세요\n플레이어의 왼쪽 이동에 매핑됩니다";
			case GEAR_UP_BUTTON -> "기어 업으로 쓸 버튼을 누르세요\n플레이어의 오른쪽 이동에 매핑됩니다";

			case BOOSTER_BUTTON -> "부스터로 쓸 버튼(또는 남는 페달)을 누르세요\n기어 다운과 같은 왼쪽 이동에 매핑됩니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case SPECIAL_BUTTON -> "익시드/차저/ERS/점프로 쓸 버튼(또는 남는 페달)을 누르세요\n플레이어의 점프에 매핑됩니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case LEFT_CLICK_BUTTON -> "좌클릭으로 쓸 버튼(또는 남는 페달)을 누르세요\n멀티 플레이에서 방장을 넘길 때 사용합니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case RIGHT_CLICK_BUTTON -> "우클릭으로 쓸 버튼(또는 남는 페달)을 누르세요\n인게임에서 버튼을 누를 때 사용합니다\n지정하지 않으려면 건너뛰기를 누르세요";

			case LOOK_UP_BUTTON -> "시선 위로 쓸 버튼(또는 남는 페달)을 누르세요\n시선이 돌아갔을 때 레이스 중에도 조정할 수 있습니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case LOOK_DOWN_BUTTON -> "시선 아래로 쓸 버튼(또는 남는 페달)을 누르세요\n시선이 돌아갔을 때 레이스 중에도 조정할 수 있습니다\n지정하지 않으려면 건너뛰기를 누르세요";

			case CROUCH_BUTTON -> "웅크리기로 쓸 버튼(또는 남는 페달)을 누르세요\n카트에서 내리거나 레이스를 포기할 때 사용합니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case SWAP_HANDS_BUTTON -> "양손 바꾸기로 쓸 버튼(또는 남는 페달)을 누르세요\n카트에 탑승하거나 위치를 리셋할 때 사용합니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case VIEW_TOGGLE_BUTTON -> "시점 전환으로 쓸 버튼(또는 남는 페달)을 누르세요\n1인칭, 2인칭, 3인칭 시점을 전환합니다\n지정하지 않으려면 건너뛰기를 누르세요";
			case HOTBAR_SHIFT_BUTTON -> "핫바 시프트로 쓸 버튼(또는 남는 페달)을 누르세요\n아이템 슬롯을 오른쪽으로 한 칸 이동합니다\n지정하지 않으려면 건너뛰기를 누르세요";

			case DONE -> "설정 완료! [다음]을 눌러 닫으세요";
		};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
