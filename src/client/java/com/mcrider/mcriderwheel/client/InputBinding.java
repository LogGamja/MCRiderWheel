package com.mcrider.mcriderwheel.client;

// 버튼, HAT 방향, 남는 축(페달 등) 중 하나만 설정되고 나머지는 -1이다
public class InputBinding {
	public int buttonIndex = -1;
	public int hatIndex = -1;
	// hatIndex >= 0일 때만 의미 있는 SDL_HAT_UP/RIGHT/DOWN/LEFT 값
	public int hatDirection = -1;
	public int axisIndex = -1;
	public float axisReleased;
	public float axisPressed;

	public InputBinding copy() {
		InputBinding c = new InputBinding();
		c.buttonIndex = buttonIndex;
		c.hatIndex = hatIndex;
		c.hatDirection = hatDirection;
		c.axisIndex = axisIndex;
		c.axisReleased = axisReleased;
		c.axisPressed = axisPressed;
		return c;
	}
}
