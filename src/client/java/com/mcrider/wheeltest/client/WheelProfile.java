package com.mcrider.wheeltest.client;

public class WheelProfile {
	public String guid;
	public String name;

	public int steerAxis = -1;
	public float steerLeft;
	public float steerCenter;
	public float steerRight;
	// Raw axis values at the wheel's actual physical lock-to-lock extremes,
	// captured during calibration regardless of the chosen lock range. Used
	// to detect how far the wheel has been turned past the configured
	// steerLeft/steerRight lock, for the soft-lock end-stop force.
	public float steerPhysicalLeft;
	public float steerPhysicalRight;
	// Raw axis units per degree of physical rotation (from the REF90
	// calibration step), 0 if unknown. Lets the soft-lock wall width be
	// defined in real degrees instead of a fraction of the raw range.
	public float steerRawPerDegree;

	// Steering rotation speed, as a percentage of the base yaw rate (see
	// WheelDrivingControl.BASE_YAW_RATE_DEG_PER_SEC). This used to default to
	// 200% purely to reproduce the fixed turn rate from before it was
	// adjustable, which left the default pinned to the slider's own maximum -
	// adjustable downwards only. 100% sits mid-range so it can go either way.
	public float steerSensitivityPercent = 100f;

	// Overall force feedback output strength, as a percentage multiplier
	// applied on top of the centering/soft-lock/pulse magnitudes. 100%
	// preserves the as-tuned defaults.
	public float ffbStrengthPercent = 100f;

	public int throttleAxis = -1;
	public float throttleReleased;
	public float throttlePressed;

	public int brakeAxis = -1;
	public float brakeReleased;
	public float brakePressed;

	// Misc inputs, each either a real button or a spare pedal/axis (e.g. a
	// clutch with nothing else to do). gearDown and booster both map to the
	// same in-game key (strafe-left/A) but are captured as separate physical
	// inputs, since MCRider's own vehicles use A for gear-down on some and
	// booster on others - one wheel button can be bound to whichever the
	// current car actually uses.
	public InputBinding gearDown = new InputBinding();
	public InputBinding gearUp = new InputBinding();
	public InputBinding booster = new InputBinding();
	public InputBinding special = new InputBinding();
	public InputBinding leftClick = new InputBinding();
	public InputBinding rightClick = new InputBinding();
	// Convenience look up/down - fixed speed, not a real control input.
	public InputBinding lookUp = new InputBinding();
	public InputBinding lookDown = new InputBinding();

	/**
	 * Whether this profile can actually steer. A calibration run that failed to
	 * identify the steering axis (bestAxis() returning -1, e.g. the wheel was
	 * never turned far enough between the LEFT/RIGHT steps) saves with
	 * steerAxis = -1, which drives nothing - WheelInput skips such a profile so
	 * a genuinely working second wheel gets picked instead of the broken one
	 * silently reporting itself as "recognized".
	 */
	public boolean isDriveable() {
		return steerAxis >= 0 && Math.abs(steerRight - steerLeft) > 1e-4f;
	}

	/** Deep copy for the calibration wizard to work on, so a mid-wizard cancel never touches the shared, currently-driving instance. */
	public WheelProfile copy() {
		WheelProfile c = new WheelProfile();
		c.guid = guid;
		c.name = name;
		c.steerAxis = steerAxis;
		c.steerLeft = steerLeft;
		c.steerCenter = steerCenter;
		c.steerRight = steerRight;
		c.steerPhysicalLeft = steerPhysicalLeft;
		c.steerPhysicalRight = steerPhysicalRight;
		c.steerRawPerDegree = steerRawPerDegree;
		c.steerSensitivityPercent = steerSensitivityPercent;
		c.ffbStrengthPercent = ffbStrengthPercent;
		c.throttleAxis = throttleAxis;
		c.throttleReleased = throttleReleased;
		c.throttlePressed = throttlePressed;
		c.brakeAxis = brakeAxis;
		c.brakeReleased = brakeReleased;
		c.brakePressed = brakePressed;
		c.gearDown = gearDown.copy();
		c.gearUp = gearUp.copy();
		c.booster = booster.copy();
		c.special = special.copy();
		c.leftClick = leftClick.copy();
		c.rightClick = rightClick.copy();
		c.lookUp = lookUp.copy();
		c.lookDown = lookDown.copy();
		return c;
	}
}
