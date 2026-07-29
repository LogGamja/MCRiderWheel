package com.mcrider.wheeltest.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Vehicle the player is driving, or is spectating someone else driving -
 * the camera entity (not client.player directly) is what covers both cases,
 * since a spectating player's own getVehicle() is always null.
 */
public final class RiddenVehicle {
	private RiddenVehicle() {
	}

	public static Entity get(Minecraft client) {
		Entity cameraEntity = client.getCameraEntity();
		return cameraEntity != null ? cameraEntity.getVehicle() : null;
	}
}
