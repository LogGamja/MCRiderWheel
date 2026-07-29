package com.mcrider.wheeltest.client;

import com.mcrider.wheeltest.client.ffb.WheelForceFeedback;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Compares a "mcrider-direction" passenger's yaw against the root vehicle's
 * actual movement heading (derived from its position delta this tick) and
 * feeds that mismatch back into the wheel's FFB: once it exceeds
 * MISMATCH_THRESHOLD_DEG, the wheel is made to resist turning harder as a
 * "something's off" cue (see WheelForceFeedback).
 */
public final class VehicleDirectionDebug {
	private static final String DIRECTION_ENTITY_NAME = "mcrider-direction";
	private static final float MISMATCH_THRESHOLD_DEG = 25f;

	private VehicleDirectionDebug() {
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
		// oldPosition()/position() are the entity's own last-tick and
		// current-tick positions, tracked by vanilla itself - no need to
		// remember state across ticks ourselves.
		Vec3 delta = root.position().subtract(root.oldPosition());
		if (delta.horizontalDistanceSqr() < 1.0e-8) {
			WheelForceFeedback.setExtraResistance(false);
			return; // not moving; heading is undefined
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
