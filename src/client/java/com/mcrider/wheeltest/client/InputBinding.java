package com.mcrider.wheeltest.client;

/**
 * A single misc input captured during BUTTONS_ONLY calibration - a real SDL
 * joystick button, a HAT (POV/D-pad) direction treated as a digital button,
 * or a spare axis (e.g. a clutch pedal with nothing else to do) treated as a
 * digital on/off press relative to its released/pressed readings, the same
 * way pedal axes are normalized elsewhere. Exactly one of
 * buttonIndex/hatIndex/axisIndex is set; -1 means unbound.
 */
public class InputBinding {
	public int buttonIndex = -1;
	public int hatIndex = -1;
	/** One of SDL's SDL_HAT_UP/RIGHT/DOWN/LEFT bit values; only meaningful when hatIndex >= 0. */
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
