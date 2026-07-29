package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

// GLFW 대신 SDL의 raw joystick API를 써서 T500 RS 같은 비표준 레이아웃 휠도
// 실제 축/버튼 그대로 읽는다. 주행/힘 피드백은 계산된 휠이 있으면 자동으로 켜지고
// 별도 켜기/끄기 키는 없다. 계산은 ESC 메뉴의 "MCRider Wheel" 화면에서 진행한다.
// 문 부수는 소리로 만드는 충돌 펄스는 패킷 레벨에서 후킹한다 (SoundPacketMixin 참고)
public class WheelClientMain implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcriderwheel");

	// GLFW의 jid와 달리 SDL의 장치 인덱스는 안정적이지 않아 GUID로 키를 잡는다
	private final Set<String> connectedGuids = new HashSet<>();
	// 연결된 조이스틱에 저장된 프로필이 없을 때 설정, 월드 로드 후 다른 화면이 없을 때 소비된다
	private boolean pendingAutoCalibration;

	@Override
	public void onInitializeClient() {
		WheelForceFeedback.init();
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			WheelForceFeedback.shutdown();
			WheelDrivingControl.shutdown(client);
			// SDL_Joystick 핸들은 WheelForceFeedback.shutdown()이 안 건드리므로 따로 닫는다
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
			// 각 서브시스템을 개별 try/catch로 묶어서, 하나가 터져도 나머지와 다음 틱은 계속 돈다
			safeTick("checkConnections", this::checkConnections);
			safeTick("WheelInput", WheelInput::tick);
			safeTick("WheelDrivingControl", () -> WheelDrivingControl.tick(client));
			// FFB보다 먼저 실행되어야 그 틱의 값을 바로 반영한다
			safeTick("DirectionMismatchFeedback", () -> DirectionMismatchFeedback.tick(client));
			safeTick("TireGripFeedback", () -> TireGripFeedback.tick(client));
			safeTick("WheelForceFeedback", WheelForceFeedback::tick);

			if (pendingAutoCalibration && client.player != null && client.screen == null) {
				pendingAutoCalibration = false;
				safeTick("open calibration screen", () -> client.setScreen(new WheelCalibrationScreen()));
			}
		});
	}

	// 네이티브 크래시는 여기서 못 잡지만, 일반 Java 예외는 로그만 남기고 다음 틱에 재시도한다
	private static void safeTick(String name, Runnable task) {
		try {
			task.run();
		} catch (Throwable t) {
			LOGGER.error("[MCRiderWheel] {} tick failed - continuing", name, t);
		}
	}

	// steerAxis = -1로 저장된 프로필(BUTTONS_ONLY만 마친 장치)도 "아직 조향 불가능"으로 취급한다
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

	// 로그로만 남긴다 - 매 월드 접속 첫 틱마다 채팅창에 뜨면 시끄럽다
	private void checkConnections() {
		Set<String> present = new HashSet<>();
		int count = SdlJoystickReader.deviceCount();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null) continue;
			present.add(guid);
			if (!connectedGuids.contains(guid)) {
				String name = SdlJoystickReader.deviceName(i);
				LOGGER.info("[MCRiderWheel] joystick connected: {} (guid={})", name, guid);

				WheelProfile profile = WheelConfig.get(guid);
				if ((profile == null || !profile.isDriveable()) && !anyCalibratedJoystickPresent()
						&& !WheelConfig.isAutoCalibrationDeclined(guid)) {
					pendingAutoCalibration = true;
				}
			}
		}
		for (String guid : connectedGuids) {
			if (!present.contains(guid)) {
				LOGGER.info("[MCRiderWheel] joystick disconnected (guid={})", guid);
			}
		}
		connectedGuids.clear();
		connectedGuids.addAll(present);
	}
}
