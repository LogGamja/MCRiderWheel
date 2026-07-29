package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

// 계산된 휠이 여러 대 연결됐을 때 실제로 운전에 쓸 장치를 고르는 화면.
// 조향 보정이 끝나 isDriveable()을 만족하는 장치만 목록에 나온다
public class WheelDeviceSelectScreen extends Screen {
	private final Screen parent;
	private int listTop;
	private List<String> guids = new ArrayList<>();

	public WheelDeviceSelectScreen(Screen parent) {
		super(Component.literal("사용할 휠 선택"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		guids = calibratedConnectedGuids();

		int btnWidth = Math.min(280, width - 20);
		int btnHeight = 20;
		int gap = 6;
		listTop = height / 2 - (guids.size() * (btnHeight + gap)) / 2;

		int y = listTop;
		for (String guid : guids) {
			WheelProfile profile = WheelConfig.get(guid);
			String name = profile.name != null && !profile.name.isEmpty() ? profile.name : guid;
			// 실제로 운전 중인 WheelInput.activeGuid 기준으로 표시한다
			// (선호 장치가 연결이 끊기면 다른 장치로 자동 전환되지만 preferredGuid 자체는 안 바뀌므로)
			String label = name + (guid.equalsIgnoreCase(WheelInput.activeGuid) ? " [선택됨]" : "");
			Button btn = Button.builder(Component.literal(label), b -> choose(guid))
					.bounds(width / 2 - btnWidth / 2, y, btnWidth, btnHeight)
					.build();
			addRenderableWidget(btn);
			y += btnHeight + gap;
		}
		if (guids.isEmpty()) {
			y += 16;
		}

		addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
				.bounds(width / 2 - btnWidth / 2, y + gap, btnWidth, btnHeight)
				.build());
	}

	private void choose(String guid) {
		// 조이스틱 핸들이 아직 이전 장치를 가리키는 동안 힘 피드백 핸들부터 닫는다
		WheelForceFeedback.releaseForDeviceChange();
		WheelConfig.setPreferredGuid(guid);
		rebuildWidgets();
	}

	private static List<String> calibratedConnectedGuids() {
		List<String> guids = new ArrayList<>();
		int count = SdlJoystickReader.deviceCount();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid == null) continue;
			WheelProfile profile = WheelConfig.get(guid);
			if (profile != null && profile.isDriveable()) {
				guids.add(guid);
			}
		}
		return guids;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, height / 2 - 90, 0xFFFFFF);
		g.drawCenteredString(font, Component.literal("조향 보정을 마치지 않은 휠은 표시되지 않습니다"),
				width / 2, listTop - 16, 0xAAAAAA);
		if (guids.isEmpty()) {
			g.drawCenteredString(font, Component.literal("표시할 휠이 없습니다"), width / 2, listTop, 0xFF5555);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
