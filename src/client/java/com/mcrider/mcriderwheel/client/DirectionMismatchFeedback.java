package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

// "mcrider-direction" 승객의 요와 차량의 실제 이동 방향을 비교해서
// 어긋난 정도가 임계값을 넘으면 휠에 저항을 더한다
public final class DirectionMismatchFeedback {
	private static final String DIRECTION_ENTITY_NAME = "mcrider-direction";
	private static final float MISMATCH_THRESHOLD_DEG = 25f;

	private DirectionMismatchFeedback() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null) {
			WheelForceFeedback.setExtraResistance(false);
			return;
		}

		Entity vehicle = RiddenVehicle.get(client);
		if (vehicle == null) {
			WheelForceFeedback.setExtraResistance(false);
			return;
		}

		Entity root = vehicle.getRootVehicle();
		Vec3 delta = root.position().subtract(root.oldPosition());
		if (delta.horizontalDistanceSqr() < 1.0e-8) {
			WheelForceFeedback.setExtraResistance(false);
			return; // 움직이지 않으면 이동 방향 자체가 정의되지 않는다
		}

		float movementYaw = (float) (Mth.atan2(-delta.x, delta.z) * (180.0 / Math.PI));

		Entity directionEntity = findDirectionPassenger(root);
		if (directionEntity == null) {
			WheelForceFeedback.setExtraResistance(false);
			return;
		}

		float entityYaw = directionEntity.getYRot();
		float diff = Mth.wrapDegrees(movementYaw - entityYaw);

		WheelForceFeedback.setExtraResistance(Math.abs(diff) > MISMATCH_THRESHOLD_DEG);
	}

	private static Entity findDirectionPassenger(Entity root) {
		for (Entity passenger : root.getPassengers()) {
			if (DIRECTION_ENTITY_NAME.equals(passenger.getName().getString())) {
				return passenger;
			}
		}
		return null;
	}
}
