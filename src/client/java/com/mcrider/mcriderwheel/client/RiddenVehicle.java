package com.mcrider.mcriderwheel.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

// 관전 중인 플레이어는 자신의 getVehicle()이 항상 null이라 카메라 엔티티 기준으로 판단한다
public final class RiddenVehicle {
	private RiddenVehicle() {
	}

	public static Entity get(Minecraft client) {
		Entity cameraEntity = client.getCameraEntity();
		return cameraEntity != null ? cameraEntity.getVehicle() : null;
	}
}
