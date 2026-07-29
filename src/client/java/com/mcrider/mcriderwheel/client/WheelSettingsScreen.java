package com.mcrider.mcriderwheel.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;
import java.util.function.Supplier;

// 계기판처럼 배치: 상단에 전체 보정 버튼, 그 아래 휠/페달/패들 3개 카드.
// 모든 크기는 화면 크기에 맞춰 1.0(풀사이즈)까지 커지고 필요한 만큼만 줄어든다
public class WheelSettingsScreen extends Screen {
	private static final float SENSITIVITY_MIN = 25f;
	private static final float SENSITIVITY_MAX = 200f;
	private static final float FFB_STRENGTH_MIN = 0f;
	private static final float FFB_STRENGTH_MAX = 200f;

	private static final int PANEL_BG = 0x77161A1E;
	private static final int PANEL_BORDER = 0x55E8A33D;
	private static final int ACCENT = 0xFFE8A33D;
	private static final int TEXT_PRIMARY = 0xFFEDEDED;
	private static final int TEXT_SECONDARY = 0xFFA0A6AD;

	private static final int FONT_H = 9;
	private static final int BUTTON_H_0 = 20;

	// "풀사이즈"(scale = 1.0) 기준값들
	private static final int COL_WIDTH_0 = 180;
	private static final int COL_GAP_0 = 14;
	private static final int ROW_GAP_0 = 4;
	private static final int ICON_AREA_0 = 64;
	private static final int PANEL_PAD_0 = 8;
	private static final int G_TITLE_RULE_0 = 10;
	private static final int G_RULE_CALIB_0 = 10;
	private static final int G_CALIB_PANELS_0 = 16;
	private static final int G_LABEL_CONTROLS_0 = 12;
	private static final int G_PANEL_TEXT_0 = 14;
	private static final int G_TEXT_CLOSE_0 = 14;
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

	// 둘 다 WheelInput.activeProfile을 직접 건드리므로, 휠이 없을 때는 슬라이더가
	// 그냥 조용히 무시되면 고장난 것처럼 보여서 아예 비활성화한다
	private PercentSlider sensitivitySlider;
	private PercentSlider ffbStrengthSlider;
	// 슬라이더가 마지막으로 동기화된 GUID
	private String syncedGuid;

	public WheelSettingsScreen(Screen parent) {
		super(Component.literal("마크라이더 레이싱 휠 설정"));
		this.parent = parent;
	}

	@Override
	protected void init() {
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
		int gTextClose = Math.max(2, gPanelText - panelPad);

		int totalWidth = colWidth * 3 + gap * 2;
		int startX = width / 2 - totalWidth / 2;
		col0X = startX;
		col1X = startX + colWidth + gap;
		col2X = startX + (colWidth + gap) * 2;

		int totalHeight = FONT_H + gTitleRule + 1 + gRuleCalib + fullCalibH + gCalibPanels
				+ iconAreaH + FONT_H + gLabelControls + rowH * 3 + gPanelText + FONT_H + gTextClose
				+ closeH + rowGap + closeH;

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

		addRenderableWidget(Button.builder(Component.literal("페달 보정"),
						b -> openCalibration(WheelCalibrationScreen.Scope.PEDALS_ONLY))
				.bounds(col1X, controlsTop, colWidth, controlH).build());

		addRenderableWidget(Button.builder(Component.literal("기타 버튼 보정"),
						b -> openCalibration(WheelCalibrationScreen.Scope.BUTTONS_ONLY))
				.bounds(col2X, controlsTop, colWidth, controlH).build());

		int closeWidth = Math.min(scaled(180, scaleW, 110), totalWidth);
		addRenderableWidget(Button.builder(Component.literal("휠 장치 변경"),
						b -> minecraft.setScreen(new WheelDeviceSelectScreen(this)))
				.bounds(width / 2 - closeWidth / 2, closeY, closeWidth, closeH).build());
		addRenderableWidget(Button.builder(Component.literal("닫기"), b -> onClose())
				.bounds(width / 2 - closeWidth / 2, closeY + closeH + rowGap, closeWidth, closeH).build());
	}

	// 풀사이즈 레이아웃이 실제 화면에 맞으면 1.0, 아니면 딱 맞게 줄이는 비율
	private float computeScaleW() {
		int fullWidthNeeded = COL_WIDTH_0 * 3 + COL_GAP_0 * 2 + 40;
		return Math.min(1f, (float) width / fullWidthNeeded);
	}

	private float computeScaleH() {
		int rowH0 = BUTTON_H_0 + ROW_GAP_0;
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

	// 저장 없이 살아있는 프로필만 바꾼다 - 슬라이더를 드래그하는 동안 매번 디스크에 쓰지 않기 위함
	private static void applyProfileLive(Consumer<WheelProfile> setter) {
		WheelProfile profile = WheelInput.activeProfile;
		if (profile != null) setter.accept(profile);
	}

	private static void persistActiveProfile() {
		profileDirty = false;
		WheelProfile profile = WheelInput.activeProfile;
		if (profile != null) WheelConfig.save(profile);
	}

	// 키보드로 슬라이더를 조작할 때 표시만 해두고, 저장은 나중에 몰아서 한다
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
		// GUID 기준으로 비교한다 - 휠 A가 빠지고 B가 이미 연결돼 있으면 available은
		// 계속 true라서 연결 여부만으로는 슬라이더 재동기화 시점을 못 잡는다
		String activeGuid = wheelConnected ? WheelInput.activeGuid : null;
		if (activeGuid != null && !activeGuid.equalsIgnoreCase(syncedGuid)) {
			flushProfileIfDirty();
			sensitivitySlider.refreshFromProfile();
			ffbStrengthSlider.refreshFromProfile();
		}
		syncedGuid = activeGuid;
		sensitivitySlider.active = wheelConnected;
		ffbStrengthSlider.active = wheelConnected;

		super.render(g, mouseX, mouseY, partialTick);
		drawHeader(g);
		drawIcons(g);
	}

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
		blitIcon(g, WHEEL_ICON, col0X + colWidth / 2, iconMidY, iconRadius);
		blitIcon(g, PEDALS_ICON, col1X + colWidth / 2, iconMidY, iconRadius);
		blitIcon(g, PADDLES_ICON, col2X + colWidth / 2, iconMidY, iconRadius);

		int labelY = panelsTop + iconAreaH + 4;
		g.drawCenteredString(font, "휠", col0X + colWidth / 2, labelY, TEXT_SECONDARY);
		g.drawCenteredString(font, "페달", col1X + colWidth / 2, labelY, TEXT_SECONDARY);
		g.drawCenteredString(font, "시프트 및 부스터", col2X + colWidth / 2, labelY, TEXT_SECONDARY);

		String detected = WheelInput.available ? "사용 중: " + WheelInput.activeDeviceName : "인식된 휠 없음";
		g.drawCenteredString(font, Component.literal(detected), width / 2, detectedY, TEXT_SECONDARY);
	}

	// 미리 렌더링된 256x256 PNG를 쓴다 (예전엔 fill()로 그린 절차적 아이콘이었음)
	private static final int ICON_TEX_SIZE = 256;
	private static final ResourceLocation WHEEL_ICON = ResourceLocation.fromNamespaceAndPath("mcriderwheel", "textures/gui/wheel_icon.png");
	private static final ResourceLocation PEDALS_ICON = ResourceLocation.fromNamespaceAndPath("mcriderwheel", "textures/gui/pedals_icon.png");
	private static final ResourceLocation PADDLES_ICON = ResourceLocation.fromNamespaceAndPath("mcriderwheel", "textures/gui/paddles_icon.png");

	// 8-인자 blit()은 목적지 크기를 소스 샘플링 영역으로도 써버려서 아이콘 크기가 작으면
	// 텍스처 귀퉁이(투명 여백)만 샘플링해 아무것도 안 보인다. 12-인자 오버로드로 소스
	// 영역을 텍스처 전체로 고정해야 실제로 확대/축소가 된다
	private static void blitIcon(GuiGraphics g, ResourceLocation texture, int cx, int cy, int radius) {
		int size = radius * 2;
		g.blit(RenderType::guiTextured, texture, cx - radius, cy - radius, 0f, 0f,
				size, size, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE, ICON_TEX_SIZE);
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

		void refreshFromProfile() {
			this.value = toNormalized(getter.get(), min, max);
			updateMessage();
		}

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
			// 드래그 중엔 메모리에 있는 프로필만 갱신한다. 실제 저장은 놓을 때 한 번뿐이다
			setter.accept(fromNormalized());
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			super.onRelease(mouseX, mouseY);
			persistActiveProfile();
		}

		// 키보드 조작은 onRelease()를 안 거치므로 dirty로 표시만 하고 나중에 몰아서 저장한다
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
