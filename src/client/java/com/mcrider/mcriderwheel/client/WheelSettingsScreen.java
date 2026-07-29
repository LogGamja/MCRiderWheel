package com.mcrider.mcriderwheel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Laid out like a small instrument cluster rather than a plain button
 * stack: a full-calibration bar on top, then three cards (wheel / pedals /
 * paddle shifters) each with a procedurally-drawn line-art icon and just
 * the controls relevant to that part of the rig.
 *
 * Every size below is a scaled fraction of a "full size" target rather
 * than a fixed pixel constant: Minecraft's logical GUI canvas varies a lot
 * (auto GUI scale can make it much smaller than the window), so a layout
 * tuned for one canvas size either looks cramped on a bigger one or runs
 * off the edges of a smaller one. The scale grows up to 1.0 (full size)
 * whenever there's room and only shrinks as far as actually needed.
 */
public class WheelSettingsScreen extends Screen {
	private static final float SENSITIVITY_MIN = 25f;
	private static final float SENSITIVITY_MAX = 200f;
	private static final float FFB_STRENGTH_MIN = 0f;
	private static final float FFB_STRENGTH_MAX = 200f;

	// Dashboard palette: graphite panels, amber backlit-gauge accent.
	private static final int PANEL_BG = 0x77161A1E;
	private static final int PANEL_BORDER = 0x55E8A33D;
	private static final int ACCENT = 0xFFE8A33D;
	private static final int TEXT_PRIMARY = 0xFFEDEDED;
	private static final int TEXT_SECONDARY = 0xFFA0A6AD;

	private static final int FONT_H = 9;
	// Vanilla button height (e.g. the pause screen's own buttons) - this is
	// the *full-size* (scale = 1.0) target; it still shrinks along with
	// everything else if the screen is too small to fit it, same as the
	// other measurements below.
	private static final int BUTTON_H_0 = 20;

	// "Full size" (scale = 1.0) targets.
	private static final int COL_WIDTH_0 = 180;
	private static final int COL_GAP_0 = 14;
	private static final int ROW_GAP_0 = 4;
	private static final int ICON_AREA_0 = 44;
	private static final int PANEL_PAD_0 = 8;
	private static final int G_TITLE_RULE_0 = 10;
	private static final int G_RULE_CALIB_0 = 10;
	private static final int G_CALIB_PANELS_0 = 16;
	private static final int G_LABEL_CONTROLS_0 = 12;
	private static final int G_PANEL_TEXT_0 = 14;
	private static final int G_TEXT_CLOSE_0 = 14;
	// How much higher than dead-center the whole card sits.
	private static final int UP_SHIFT = 32;

	private final Screen parent;

	private int colWidth;
	private int panelsTop;
	private int iconAreaH;
	private int col0X;
	private int col1X;
	private int col2X;
	private int panelBottom;
	private int panelPad;
	private int titleY;
	private int ruleY;
	private int detectedY;

	// Both mutate WheelInput.activeProfile (see applyProfileLive) - with no
	// wheel connected that's a silent no-op, which looks like a broken
	// slider, so these are disabled instead whenever WheelInput.available
	// is false (checked live in render(), since a wheel can connect/
	// disconnect while this screen is open).
	private PercentSlider sensitivitySlider;
	private PercentSlider ffbStrengthSlider;
	// Edge-detects a wheel connecting while this screen is already open - see
	// render()'s use of this below.
	private boolean wasWheelConnected;

	public WheelSettingsScreen(Screen parent) {
		super(Component.literal("MCRider Wheel Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// Width and height are scaled independently: on a wide-but-short
		// logical canvas (common at high GUI Scale settings), the 3 columns
		// can be the only thing that's tight, and that must not also shrink
		// button *height* - there's plenty of vertical room for that.
		float scaleW = computeScaleW();
		float scaleH = computeScaleH();

		colWidth = scaled(COL_WIDTH_0, scaleW, 70);
		int gap = scaled(COL_GAP_0, scaleW, 6);
		int rowGap = scaled(ROW_GAP_0, scaleH, 2);
		int buttonH = scaled(BUTTON_H_0, scaleH, 12);
		int rowH = buttonH + rowGap;
		iconAreaH = scaled(ICON_AREA_0, scaleH, 24);
		panelPad = scaled(PANEL_PAD_0, Math.min(scaleW, scaleH), 4);
		int fullCalibH = buttonH;
		int closeH = buttonH;
		int gTitleRule = scaled(G_TITLE_RULE_0, scaleH, 4);
		int gRuleCalib = scaled(G_RULE_CALIB_0, scaleH, 4);
		int gCalibPanels = scaled(G_CALIB_PANELS_0, scaleH, 8);
		int gLabelControls = scaled(G_LABEL_CONTROLS_0, scaleH, 6);
		int gPanelText = scaled(G_PANEL_TEXT_0, scaleH, 6);
		// The visible gap *above* the detected-wheel text isn't gPanelText: the
		// panel is drawn out to panelBottom + panelPad, so the text actually
		// clears the panel's edge by gPanelText - panelPad. Matching that below
		// the text (rather than using the raw gPanelText) is what makes the line
		// sit evenly between the panels and the buttons instead of hugging the
		// panels, and pulls the button stack up by the same amount.
		int gTextClose = Math.max(2, gPanelText - panelPad);

		int totalWidth = colWidth * 3 + gap * 2;
		int startX = width / 2 - totalWidth / 2;
		col0X = startX;
		col1X = startX + colWidth + gap;
		col2X = startX + (colWidth + gap) * 2;

		// The device-change button is always present rather than appearing only
		// when 2+ calibrated wheels happen to be connected: a control that
		// silently vanishes (and shifts everything below it) whenever a wheel is
		// unplugged, or whose profile isn't saved yet, reads as a bug. Its own
		// screen already handles the trivial cases.
		int totalHeight = FONT_H + gTitleRule + 1 + gRuleCalib + fullCalibH + gCalibPanels
				+ iconAreaH + FONT_H + gLabelControls + rowH * 3 + gPanelText + FONT_H + gTextClose
				+ closeH + rowGap + closeH;

		// Shifted up from dead-center by UP_SHIFT, but not so far that the card's
		// bottom (closeY + closeH) would run off the screen - on a short-but-wide
		// canvas (computeScaleH() already near 1.0 since width isn't the tight
		// dimension there) the naive dead-center-minus-shift placement could push
		// the close button past the bottom edge with no scale-driven shrink to catch it.
		int idealTop = height / 2 - totalHeight / 2 - scaled(UP_SHIFT, scaleH, 8);
		int maxTop = Math.max(4, height - totalHeight - 4);
		int cursor = Math.max(4, Math.min(idealTop, maxTop));
		titleY = cursor;
		cursor += FONT_H + gTitleRule;
		ruleY = cursor;
		cursor += 1 + gRuleCalib;
		int fullCalibY = cursor;
		cursor += fullCalibH + gCalibPanels;
		panelsTop = cursor;
		cursor += iconAreaH;
		int labelY = cursor;
		cursor += FONT_H + gLabelControls;
		int controlsTop = cursor;
		cursor += rowH * 3;
		panelBottom = cursor;
		cursor += gPanelText;
		detectedY = cursor;
		cursor += FONT_H + gTextClose;
		int closeY = cursor;

		int fullCalibWidth = Math.min(scaled(260, scaleW, 140), totalWidth);
		addRenderableWidget(Button.builder(Component.literal("전체 보정"),
						b -> openCalibration(WheelCalibrationScreen.Scope.FULL))
				.bounds(width / 2 - fullCalibWidth / 2, fullCalibY, fullCalibWidth, fullCalibH).build());

		int controlH = buttonH;

		// Wheel column: calibration + the two rotation/force sliders.
		addRenderableWidget(Button.builder(Component.literal("휠 보정"),
						b -> openCalibration(WheelCalibrationScreen.Scope.WHEEL_ONLY))
				.bounds(col0X, controlsTop, colWidth, controlH).build());
		sensitivitySlider = new PercentSlider(col0X, controlsTop + rowH, colWidth, controlH,
				"회전 감도", SENSITIVITY_MIN, SENSITIVITY_MAX,
				() -> profileOr(100f, p -> p.steerSensitivityPercent),
				v -> applyProfileLive(p -> p.steerSensitivityPercent = v));
		addRenderableWidget(sensitivitySlider);
		ffbStrengthSlider = new PercentSlider(col0X, controlsTop + rowH * 2, colWidth, controlH,
				"FFB 강도", FFB_STRENGTH_MIN, FFB_STRENGTH_MAX,
				() -> profileOr(100f, p -> p.ffbStrengthPercent),
				v -> applyProfileLive(p -> p.ffbStrengthPercent = v));
		addRenderableWidget(ffbStrengthSlider);

		// Pedals column: just calibration.
		addRenderableWidget(Button.builder(Component.literal("페달 보정"),
						b -> openCalibration(WheelCalibrationScreen.Scope.PEDALS_ONLY))
				.bounds(col1X, controlsTop, colWidth, controlH).build());

		// Paddle-shifter / misc button column: just calibration.
		addRenderableWidget(Button.builder(Component.literal("기타 버튼 보정"),
						b -> openCalibration(WheelCalibrationScreen.Scope.BUTTONS_ONLY))
				.bounds(col2X, controlsTop, colWidth, controlH).build());

		// Device picker stacked directly above [닫기], both the same width.
		int closeWidth = Math.min(scaled(180, scaleW, 110), totalWidth);
		addRenderableWidget(Button.builder(Component.literal("휠 장치 변경"),
						b -> minecraft.setScreen(new WheelDeviceSelectScreen(this)))
				.bounds(width / 2 - closeWidth / 2, closeY, closeWidth, closeH).build());
		addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
				.bounds(width / 2 - closeWidth / 2, closeY + closeH + rowGap, closeWidth, closeH).build());
	}

	/**
	 * 1.0 when the full-size layout fits the actual screen; otherwise the
	 * ratio that makes it just fit, so the whole card shrinks together
	 * instead of individual pieces clipping or overlapping.
	 */
	private float computeScaleW() {
		int fullWidthNeeded = COL_WIDTH_0 * 3 + COL_GAP_0 * 2 + 40;
		return Math.min(1f, (float) width / fullWidthNeeded);
	}

	private float computeScaleH() {
		int rowH0 = BUTTON_H_0 + ROW_GAP_0;
		// Trailing "+ rowH0" is the second stacked bottom button ([닫기] under
		// [휠 장치 변경]); G_TEXT_CLOSE_0 is deliberately not part of this - the
		// gap below the detected-wheel text is derived from G_PANEL_TEXT_0 in
		// init() so it visually matches the gap above it.
		int fullHeightNeeded = FONT_H + G_TITLE_RULE_0 + 1 + G_RULE_CALIB_0 + BUTTON_H_0 + G_CALIB_PANELS_0
				+ ICON_AREA_0 + FONT_H + G_LABEL_CONTROLS_0 + rowH0 * 3 + G_PANEL_TEXT_0 + FONT_H + G_PANEL_TEXT_0
				+ BUTTON_H_0 + rowH0 + UP_SHIFT + 30;
		return Math.min(1f, (float) height / fullHeightNeeded);
	}

	private static int scaled(int full, float scale, int min) {
		return Math.max(min, Math.round(full * scale));
	}

	private void openCalibration(WheelCalibrationScreen.Scope scope) {
		minecraft.setScreen(new WheelCalibrationScreen(scope, this));
	}

	private static float profileOr(float fallback, java.util.function.Function<WheelProfile, Float> getter) {
		WheelProfile profile = WheelInput.activeProfile;
		return profile != null ? getter.apply(profile) : fallback;
	}

	/** Mutates the live in-memory profile without saving - for use on every drag tick of a slider (see persistActiveProfile). */
	private static void applyProfileLive(Consumer<WheelProfile> setter) {
		WheelProfile profile = WheelInput.activeProfile;
		if (profile != null) setter.accept(profile);
	}

	private static void persistActiveProfile() {
		profileDirty = false;
		WheelProfile profile = WheelInput.activeProfile;
		if (profile != null) WheelConfig.save(profile);
	}

	// Set by keyboard slider adjustments, which repeat far too fast to write
	// the config file on each one - see PercentSlider.keyPressed().
	private static boolean profileDirty;

	private static void flushProfileIfDirty() {
		if (profileDirty) persistActiveProfile();
	}

	@Override
	public void onClose() {
		flushProfileIfDirty();
		minecraft.setScreen(parent);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		boolean wheelConnected = WheelInput.available;
		// Sliders are constructed in init() reading whatever profile was
		// active *then* - if no wheel was connected yet, that's the 100%
		// fallback both sliders share (see profileOr), not this device's
		// actual saved values.
		// Without this, a wheel connecting later left the handle sitting at
		// that fallback position forever (just newly draggable), so the
		// first touch would silently overwrite a real non-default saved
		// value with whatever the fallback-derived handle position implied.
		if (wheelConnected && !wasWheelConnected) {
			sensitivitySlider.refreshFromProfile();
			ffbStrengthSlider.refreshFromProfile();
		}
		wasWheelConnected = wheelConnected;
		sensitivitySlider.active = wheelConnected;
		ffbStrengthSlider.active = wheelConnected;

		super.render(g, mouseX, mouseY, partialTick);
		drawHeader(g);
		drawIcons(g);
	}

	/**
	 * Panels belong above the screen background but below the buttons, and
	 * Screen.render() draws both of those in one call (background first, then
	 * every widget) - so drawing them from render() before super.render() put
	 * them under the full-screen menu-background blit instead of on top of it.
	 */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		drawPanels(g);
	}

	private void drawHeader(GuiGraphics g) {
		g.drawCenteredString(font, title, width / 2, titleY, TEXT_PRIMARY);
		int ruleHalf = Math.min(90, (col2X + colWidth - col0X) / 2);
		g.fill(width / 2 - ruleHalf, ruleY, width / 2 + ruleHalf, ruleY + 1, PANEL_BORDER);
	}

	private void drawPanels(GuiGraphics g) {
		int top = panelsTop - panelPad;
		int bottom = panelBottom + panelPad;
		drawPanel(g, col0X - panelPad, top, col0X + colWidth + panelPad, bottom);
		drawPanel(g, col1X - panelPad, top, col1X + colWidth + panelPad, bottom);
		drawPanel(g, col2X - panelPad, top, col2X + colWidth + panelPad, bottom);
	}

	private static void drawPanel(GuiGraphics g, int x1, int y1, int x2, int y2) {
		g.fill(x1, y1, x2, y2, PANEL_BG);
		g.fill(x1, y1, x2, y1 + 1, PANEL_BORDER);
		g.fill(x1, y2 - 1, x2, y2, PANEL_BORDER);
		g.fill(x1, y1, x1 + 1, y2, PANEL_BORDER);
		g.fill(x2 - 1, y1, x2, y2, PANEL_BORDER);
	}

	private void drawIcons(GuiGraphics g) {
		int iconMidY = panelsTop + iconAreaH / 2;
		int iconRadius = Math.max(9, iconAreaH / 2 - 4);
		drawWheelIcon(g, col0X + colWidth / 2, iconMidY, iconRadius);
		drawPedalsIcon(g, col1X + colWidth / 2, iconMidY, iconRadius);
		drawPaddlesIcon(g, col2X + colWidth / 2, iconMidY, iconRadius);

		int labelY = panelsTop + iconAreaH + 4;
		g.drawCenteredString(font, "휠", col0X + colWidth / 2, labelY, TEXT_SECONDARY);
		g.drawCenteredString(font, "페달", col1X + colWidth / 2, labelY, TEXT_SECONDARY);
		g.drawCenteredString(font, "시프트 및 부스터", col2X + colWidth / 2, labelY, TEXT_SECONDARY);

		String detected = WheelInput.available ? "사용 중: " + WheelInput.activeDeviceName : "인식된 휠 없음";
		g.drawCenteredString(font, Component.literal(detected), width / 2, detectedY, TEXT_SECONDARY);
	}

	// --- procedural line-art icons -------------------------------------------------

	private static void dot(GuiGraphics g, int x, int y) {
		g.fill(x - 1, y - 1, x + 1, y + 1, ACCENT);
	}

	private static void drawWheelIcon(GuiGraphics g, int cx, int cy, int radius) {
		for (int deg = 0; deg < 360; deg += 8) {
			double rad = Math.toRadians(deg);
			int x = cx + (int) Math.round(radius * Math.cos(rad));
			int y = cy + (int) Math.round(radius * Math.sin(rad));
			dot(g, x, y);
		}
		g.fill(cx - radius + 3, cy, cx + radius - 3, cy + 1, ACCENT); // horizontal spoke
		g.fill(cx, cy, cx + 1, cy + radius - 3, ACCENT); // spoke down to the rim
	}

	private static void drawPedalsIcon(GuiGraphics g, int cx, int cy, int radius) {
		int w = Math.max(8, radius * 2 / 3);
		int h = Math.max(14, radius * 4 / 3);
		int gap = Math.max(4, radius / 3);
		int totalW = w * 3 + gap * 2;
		int startX = cx - totalW / 2;
		int top = cy - h / 2 - 2;
		for (int i = 0; i < 3; i++) {
			int x = startX + i * (w + gap);
			outlineRect(g, x, top, x + w, top + h);
			g.fill(x + w / 2, top + h, x + w / 2 + 1, top + h + Math.max(5, radius / 2), ACCENT); // pedal arm stem
		}
	}

	private static void outlineRect(GuiGraphics g, int x1, int y1, int x2, int y2) {
		g.fill(x1, y1, x2, y1 + 1, ACCENT);
		g.fill(x1, y2 - 1, x2, y2, ACCENT);
		g.fill(x1, y1, x1 + 1, y2, ACCENT);
		g.fill(x2 - 1, y1, x2, y2, ACCENT);
	}

	private static void drawPaddlesIcon(GuiGraphics g, int cx, int cy, int radius) {
		int offset = Math.max(8, radius * 3 / 4);
		drawArc(g, cx - offset, cy, radius, 130, 230); // left paddle, opens right - "("
		drawArc(g, cx + offset, cy, radius, -50, 50);  // right paddle, opens left - ")"
	}

	private static void drawArc(GuiGraphics g, int cx, int cy, int radius, int startDeg, int endDeg) {
		for (int deg = startDeg; deg <= endDeg; deg += 6) {
			double rad = Math.toRadians(deg);
			int x = cx + (int) Math.round(radius * Math.cos(rad));
			int y = cy + (int) Math.round(radius * Math.sin(rad));
			dot(g, x, y);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static class PercentSlider extends AbstractSliderButton {
		private final float min;
		private final float max;
		private final String label;
		private final Supplier<Float> getter;
		private final Consumer<Float> setter;

		PercentSlider(int x, int y, int width, int height, String label, float min, float max,
				Supplier<Float> getter, Consumer<Float> setter) {
			super(x, y, width, height, Component.empty(), toNormalized(getter.get(), min, max));
			this.min = min;
			this.max = max;
			this.label = label;
			this.getter = getter;
			this.setter = setter;
			updateMessage();
		}

		/** Re-syncs the handle position from the live profile - see render()'s use of this. */
		void refreshFromProfile() {
			this.value = toNormalized(getter.get(), min, max);
			updateMessage();
		}

		// Clamped: a profile saved under a previous, wider min/max (e.g. the
		// FFB slider's cap dropping from 200% to 100%) would otherwise put
		// the handle outside the track on load.
		private static double toNormalized(float v, float min, float max) {
			return Math.max(0.0, Math.min(1.0, (v - min) / (max - min)));
		}

		private float fromNormalized() {
			return min + (float) value * (max - min);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + (int) fromNormalized() + "%"));
		}

		@Override
		protected void applyValue() {
			// Called on every mouse-move while dragging - setter here only
			// touches the in-memory profile (see applyProfileLive), so the
			// slider still feels live without hitting disk on every pixel
			// of drag. The actual save happens once, on release.
			setter.accept(fromNormalized());
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			super.onRelease(mouseX, mouseY);
			persistActiveProfile();
		}

		// AbstractSliderButton's own keyPressed() (left/right arrow while
		// focused) adjusts the value and calls applyValue() directly - it
		// never goes through onRelease(), so a keyboard-only adjustment was
		// never actually saved to disk (it looked saved for the rest of the
		// session since the in-memory profile did update, but reverted on
		// the next launch). Rather than writing the whole config file per
		// accepted key press - GLFW repeats a held arrow key tens of times a
		// second, and each write re-serializes every saved profile - this only
		// marks it dirty; the flush happens once the screen closes (or focus
		// leaves the slider), which is soon enough and can't thrash the disk.
		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
			if (handled) profileDirty = true;
			return handled;
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused) flushProfileIfDirty();
		}
	}
}
