package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import org.lwjgl.glfw.GLFW;

// 계산된 휠이 연결되면 자동으로 살아나는 조작. 스로틀/브레이크는 전진/후진,
// 조향은 각도에 비례한 요 회전 속도(러더처럼) 이며, 기어/부스터 버튼은 좌우 이동에 매핑된다.
// 각 키는 (휠이 원함) OR (실제 물리 키보드 눌림)으로 설정하고, 물리 키 상태는
// 우리가 쓴 값이 아니라 매 틱 GLFW에서 새로 읽는다
public class WheelDrivingControl {
	public static final float BASE_YAW_RATE_DEG_PER_SEC = 270f;
	// 페달을 이진 키로 흉내내려면, 살짝 밟은 것과 끝까지 밟은 것 사이 구간을
	// PWM 듀티 사이클로 빠르게 온/오프 탭핑해서 미세 제어를 흉내낸다
	private static final float PEDAL_PWM_LOW = 0.05f;
	private static final float PEDAL_PWM_HIGH = 0.95f;
	// 정지 상태에서도 미세하게 흔들리는 값이 매 틱 요를 조금씩 돌리지 않도록 하는 데드존
	private static final float STEER_DEADZONE = 0.01f;
	// 렉/GC로 프레임 간격이 튀어도 회전이 한 번에 몰아서 튀지 않도록 상한
	private static final float MAX_FRAME_DELTA_SECONDS = 0.1f;
	// 시선 위/아래는 고정 속도 편의 기능이라 조향 감도의 영향을 받지 않는다
	private static final float PITCH_RATE_DEG_PER_SEC = 90f;

	// 게임 틱(20Hz)이 아니라 실제 프레임 간격으로 회전을 적용해서 부드럽게 만든다
	private static long lastFrameNanos = -1;
	// 델타-시그마 PWM 누적기: 매 틱 듀티를 더하다가 1.0을 넘으면 펄스를 낸다
	private static float throttlePwmAccumulator;
	private static float brakePwmAccumulator;
	// setDown()만으로는 단발 클릭 액션(공격 등)이 안 나가서 상승 엣지에 click()이 따로 필요하다
	private static boolean prevWheelAttack;
	private static boolean prevWheelSwapHands;
	private static boolean prevWheelViewToggle;
	private static boolean prevWheelHotbarShift;
	// 직전 틱에 휠로 실제 주행 중이었는지 - tick()의 하강 엣지 해제에 사용
	private static boolean wasWheelReady;

	private WheelDrivingControl() {
	}

	// 페달이 PWM으로 전진 키를 두드리는 동안 바닐라의 더블탭 스프린트 감지를 꺼둬야 하는지
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
		// armedAxisBindings는 InputBinding 인스턴스로 키를 잡으므로, 재보정으로
		// 새 인스턴스가 생기면 옛 것들이 계속 쌓이지 않도록 GUID가 바뀔 때마다 비운다
		String currentGuid = WheelInput.activeGuid;
		if (!java.util.Objects.equals(currentGuid, lastArmedGuid)) {
			armedAxisBindings.clear();
			lastArmedGuid = currentGuid;
		}

		// 채팅/메뉴 조작 중이거나 창이 비활성 상태면 주입 키로 방해하지 않는다
		if (client.player == null || client.screen != null || !client.isWindowActive()) {
			// 이 클래스가 실제로 키를 쥐고 있었을 때만, 하강 엣지에서 한 번만 해제한다
			if (wasWheelReady) {
				releaseAll(client);
				wasWheelReady = false;
			}
			return;
		}

		boolean wheelReady = WheelInput.available;
		WheelProfile profile = wheelReady ? WheelInput.activeProfile : null;

		boolean wheelUp = wheelReady && pwmPulse(WheelInput.throttle, true);
		boolean wheelDown = wheelReady && pwmPulse(WheelInput.brake, false);
		// gearDown과 booster 둘 다 좌측 이동에 매핑, 어느 게 쓰이는지는 탄 차량에 따라 다르다
		boolean wheelLeft = wheelReady && profile != null && (isDown(profile.gearDown) || isDown(profile.booster));
		boolean wheelRight = wheelReady && profile != null && isDown(profile.gearUp);
		boolean wheelJump = wheelReady && profile != null && isDown(profile.special);
		boolean wheelAttack = wheelReady && profile != null && isDown(profile.leftClick);
		boolean wheelUse = wheelReady && profile != null && isDown(profile.rightClick);
		boolean wheelCrouch = wheelReady && profile != null && isDown(profile.crouch);
		boolean wheelSwapHands = wheelReady && profile != null && isDown(profile.swapHands);
		boolean wheelViewToggle = wheelReady && profile != null && isDown(profile.viewToggle);
		boolean wheelHotbarShift = wheelReady && profile != null && isDown(profile.hotbarShift);

		// 바닐라 AFK 감지기는 실제 GLFW 콜백만 보고 우리가 주입한 키는 못 보므로
		// 여기서 직접 깨워줘야 휠로만 운전할 때 게임이 AFK로 착각하지 않는다
		boolean wheelActive = wheelReady && (wheelLeft || wheelRight || wheelJump || wheelAttack || wheelUse
				|| wheelCrouch || wheelSwapHands || wheelViewToggle || wheelHotbarShift
				|| WheelInput.throttle > PEDAL_PWM_LOW || WheelInput.brake > PEDAL_PWM_LOW
				|| Math.abs(WheelInput.steering) > STEER_DEADZONE);
		if (wheelActive) {
			client.getFramerateLimitTracker().onInputReceived();
		}

		// 휠이 실제로 주행 중일 때만 건드린다 - 그래야 휠이 없을 때 다른 모드가
		// setDown()으로 쥔 키를 물리 키 상태로 덮어쓰지 않는다
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
			// 휠 연결이 끊긴 순간 한 번만 물리 상태로 되돌린다 (안 그러면 키가 계속 눌린 채로 남음)
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

		// 양손 바꾸기/시점 전환은 바닐라 키바인드를 그대로 클릭 처리로 위임한다
		if (wheelSwapHands && !prevWheelSwapHands) {
			KeyMapping.click(KeyBindingHelper.getBoundKeyOf(client.options.keySwapOffhand));
		}
		prevWheelSwapHands = wheelSwapHands;

		if (wheelViewToggle && !prevWheelViewToggle) {
			KeyMapping.click(KeyBindingHelper.getBoundKeyOf(client.options.keyTogglePerspective));
		}
		prevWheelViewToggle = wheelViewToggle;

		// 핫바를 한 칸 옮기는 바닐라 키바인드가 없어서 슬롯을 직접 설정한다
		if (wheelHotbarShift && !prevWheelHotbarShift) {
			Inventory inventory = client.player.getInventory();
			inventory.setSelectedSlot((inventory.getSelectedSlot() + 1) % Inventory.SELECTION_SIZE);
		}
		prevWheelHotbarShift = wheelHotbarShift;

		// PWM 탭 패턴이 더블탭 W처럼 보여서 바닐라가 스프린트를 착각할 수 있으므로
		// 페달이 실제로 쓰이는 동안은 여기서 스프린트 여부를 직접 결정한다
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

	// 우리가 덮어쓴 값 말고 실제 하드웨어 키/마우스 상태를 읽는다
	private static boolean isPhysicallyDown(Minecraft client, KeyMapping mapping) {
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

	// 게임 틱이 아니라 매 렌더 프레임마다 호출되어 마우스룩처럼 부드럽게 회전한다
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

		// 20Hz 틱 캐시값 대신 SDL 축을 프레임마다 새로 읽어서 조향 지연을 줄인다
		float liveSteering = WheelInput.steeringNow();

		float yawDelta = 0f;
		if (Math.abs(liveSteering) > STEER_DEADZONE) {
			float sensitivityPercent = profile != null ? profile.steerSensitivityPercent : 100f;
			float maxYawRate = BASE_YAW_RATE_DEG_PER_SEC * sensitivityPercent / 100f;
			yawDelta = liveSteering * maxYawRate * deltaSeconds;
		}

		float pitchDelta = 0f;
		if (profile != null) {
			if (isDown(profile.lookUp)) pitchDelta -= PITCH_RATE_DEG_PER_SEC * deltaSeconds;
			if (isDown(profile.lookDown)) pitchDelta += PITCH_RATE_DEG_PER_SEC * deltaSeconds;
		}

		if (yawDelta != 0f || pitchDelta != 0f) {
			// Entity.turn()은 인자에 0.15를 곱하고 yRotO/xRotO도 같이 갱신해서
			// 틱 사이 카메라 보간이 어긋나지 않게 한다 (기존 수동 clamp 방식과 동일한 효과)
			client.player.turn(yawDelta / 0.15, pitchDelta / 0.15);
		}
	}

	private static boolean isDown(InputBinding binding) {
		if (binding == null || WheelInput.activeGuid == null) return false;

		if (binding.buttonIndex >= 0) {
			if (binding.buttonIndex >= SdlJoystickReader.buttonCount()) return false;
			return SdlJoystickReader.buttonDown(binding.buttonIndex);
		}

		if (binding.hatIndex >= 0) {
			if (binding.hatIndex >= SdlJoystickReader.hatCount()) return false;
			// 비트 AND 비교라서 두 방향이 겹친 대각선 값도 각 방향에 대해 눌림으로 판정된다
			return (SdlJoystickReader.hatValue(binding.hatIndex) & binding.hatDirection) != 0;
		}

		if (binding.axisIndex >= 0) {
			if (binding.axisIndex >= SdlJoystickReader.axisCount()) return false;
			float span = binding.axisPressed - binding.axisReleased;
			if (Math.abs(span) < 1e-4f) return false;
			float t = (SdlJoystickReader.axisValue(binding.axisIndex) - binding.axisReleased) / span;
			boolean pressed = t > 0.5f;

			// 재연결 직후 축이 잠깐 이미 눌린 값을 보고할 수 있어서, 진짜 released
			// 값을 한 번 보기 전까지는 눌림으로 인정하지 않는다
			if (!pressed) {
				armedAxisBindings.add(binding);
			}
			return pressed && armedAxisBindings.contains(binding);
		}

		return false;
	}

	// InputBinding은 equals/hashCode가 없어서 참조 동일성으로 키를 잡는다
	private static final java.util.Set<InputBinding> armedAxisBindings =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private static String lastArmedGuid;
}
