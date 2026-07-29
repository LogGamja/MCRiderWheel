package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import com.mcrider.mcriderwheel.client.sdl.SdlJoystickReader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the player explicitly pick which calibrated wheel to actually drive
 * with when more than one is connected at once - without this,
 * {@link WheelInput#tick()} just uses whichever calibrated device SDL
 * happens to enumerate first, which isn't necessarily stable across
 * reconnects/reboots. The choice is persisted via
 * {@link WheelConfig#setPreferredGuid}, and only takes effect for devices
 * that are both connected and calibrated at the time WheelInput reads it -
 * an unplugged preferred wheel just falls back to the old first-found
 * behavior rather than leaving nothing driveable.
 */
public class WheelDeviceSelectScreen extends Screen {
	private final Screen parent;

	public WheelDeviceSelectScreen(Screen parent) {
		super(Component.literal("사용할 휠 선택"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		List<String> guids = calibratedConnectedGuids();
		String preferredGuid = WheelConfig.getPreferredGuid();

		int btnWidth = Math.min(280, width - 20);
		int btnHeight = 20;
		int gap = 6;
		int autoBtnY = height / 2 - ((guids.size() + 1) * (btnHeight + gap)) / 2;

		addRenderableWidget(Button.builder(
						Component.literal("첫 번째 휠 선택" + (preferredGuid == null ? " [선택됨]" : "")),
						b -> choose(null))
				.bounds(width / 2 - btnWidth / 2, autoBtnY, btnWidth, btnHeight)
				.build());

		int y = autoBtnY + btnHeight + gap;
		for (String guid : guids) {
			WheelProfile profile = WheelConfig.get(guid);
			String name = profile.name != null && !profile.name.isEmpty() ? profile.name : guid;
			// Only the explicit preference is marked. "Currently in use" would
			// almost always duplicate it (WheelInput picks the preferred device
			// whenever it's connected), so showing both just reads as noise.
			String label = name + (guid.equalsIgnoreCase(preferredGuid) ? " [선택됨]" : "");
			Button btn = Button.builder(Component.literal(label), b -> choose(guid))
					.bounds(width / 2 - btnWidth / 2, y, btnWidth, btnHeight)
					.build();
			addRenderableWidget(btn);
			y += btnHeight + gap;
		}

		addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
				.bounds(width / 2 - btnWidth / 2, y + gap, btnWidth, btnHeight)
				.build());
	}

	private void choose(String guid) {
		// Before the preference lands, so the haptic handle is closed while the
		// joystick handle it was opened from is still the old device's - see
		// WheelForceFeedback.releaseForDeviceChange().
		WheelForceFeedback.releaseForDeviceChange();
		WheelConfig.setPreferredGuid(guid);
		// Stay open and rebuild instead of closing, so the [선택됨] marker moves
		// to what was just picked and the player can see the choice took (and
		// change their mind) without reopening the screen.
		rebuildWidgets();
	}

	/** Every currently-connected device that already has a saved WheelProfile, in SDL's own enumeration order. Package-visible so WheelSettingsScreen can decide whether picking between them is even relevant. */
	static List<String> calibratedConnectedGuids() {
		List<String> guids = new ArrayList<>();
		int count = SdlJoystickReader.deviceCount();
		for (int i = 0; i < count; i++) {
			String guid = SdlJoystickReader.deviceGuid(i);
			if (guid != null && WheelConfig.get(guid) != null) {
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
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
