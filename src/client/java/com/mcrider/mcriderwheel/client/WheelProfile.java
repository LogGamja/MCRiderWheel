package com.mcrider.mcriderwheel.client;

public class WheelProfile {
	public String guid;
	public String name;

	public int steerAxis = -1;
	public float steerLeft;
	public float steerCenter;
	public float steerRight;
	// 보정 중 측정한 물리적 끝단 값. 설정한 락을 넘어간 정도(오버트래블) 계산에 사용
	public float steerPhysicalLeft;
	public float steerPhysicalRight;
	// REF90 단계에서 구한 1도당 raw 축 단위. 소프트락 벽 폭을 각도 단위로 잡기 위함
	public float steerRawPerDegree;

	public float steerSensitivityPercent = 100f;

	public float ffbStrengthPercent = 100f;

	public int throttleAxis = -1;
	public float throttleReleased;
	public float throttlePressed;

	public int brakeAxis = -1;
	public float brakeReleased;
	public float brakePressed;

	// gearDown과 booster는 둘 다 좌측 이동 키에 매핑됨 (MCRider 차량마다 쓰는 게 다름)
	public InputBinding gearDown = new InputBinding();
	public InputBinding gearUp = new InputBinding();
	public InputBinding booster = new InputBinding();
	public InputBinding special = new InputBinding();
	public InputBinding leftClick = new InputBinding();
	public InputBinding rightClick = new InputBinding();
	// 고정 속도 시선 조작용, 실제 주행 입력은 아님
	public InputBinding lookUp = new InputBinding();
	public InputBinding lookDown = new InputBinding();
	public InputBinding crouch = new InputBinding();
	public InputBinding swapHands = new InputBinding();
	public InputBinding viewToggle = new InputBinding();
	public InputBinding hotbarShift = new InputBinding();

	// 조향축을 못 찾은 보정(steerAxis = -1)은 주행 불가능한 상태
	public boolean isDriveable() {
		return steerAxis >= 0 && Math.abs(steerRight - steerLeft) > 1e-4f;
	}

	// 보정 위저드가 취소돼도 실제 사용 중인 프로필이 건드려지지 않도록 깊은 복사
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
		c.crouch = crouch.copy();
		c.swapHands = swapHands.copy();
		c.viewToggle = viewToggle.copy();
		c.hotbarShift = hotbarShift.copy();
		return c;
	}
}
