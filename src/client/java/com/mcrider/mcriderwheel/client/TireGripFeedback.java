package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

// MCRider의 엔진 1006 카트는 전용 API가 없어서, 차량의 ARMOR 속성에 붙은
// 이름 붙은 AttributeModifier("data-engine-real", "state-drift")를 데이터로 읽는다.
// 그립 게이지는 플레이어의 XP 진행도이고, state-drift 플래그가 실제 슬립 여부를 알려준다
// (슬립이 발생하면 XP가 20% 정도로 리셋되어 그냥 그립 중인 상태와 구분이 안 되기 때문)
public final class TireGripFeedback {
	private static final int GRIP_ENGINE_ID = 1006;
	private static final float GRIP_RAMP_START = 0.25f;
	private static final float GRIP_RAMP_MAX_MAGNITUDE = 0.18f;

	private TireGripFeedback() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null) {
			reset();
			return;
		}

		// 서버가 관전 대상의 XP를 관전자 본인 게이지에 그대로 미러링하므로
		// 누구를 타고 있든 client.player 기준으로 읽으면 된다
		Entity vehicle = RiddenVehicle.get(client);
		if (!(vehicle instanceof LivingEntity livingVehicle)) {
			reset();
			return;
		}

		AttributeInstance armor = livingVehicle.getAttribute(Attributes.ARMOR);
		if (armor == null) {
			reset();
			return;
		}

		Double engineId = modifierAmount(armor, "data-engine-real");
		if (engineId == null || Math.round(engineId) != GRIP_ENGINE_ID) {
			reset();
			return;
		}

		Double drifting = modifierAmount(armor, "state-drift");
		boolean isDrifting = drifting != null && drifting >= 0.5;
		WheelForceFeedback.setTiresBrokenLoose(isDrifting);

		float gripProgress = isDrifting ? 1f : client.player.experienceProgress;
		float magnitude = gripProgress > GRIP_RAMP_START
				? (gripProgress - GRIP_RAMP_START) / (1f - GRIP_RAMP_START) * GRIP_RAMP_MAX_MAGNITUDE
				: 0f;

		WheelForceFeedback.setGripVibrationMagnitude(magnitude);
	}

	private static void reset() {
		WheelForceFeedback.setGripVibrationMagnitude(0f);
		WheelForceFeedback.setTiresBrokenLoose(false);
	}

	// 모디파이어의 네임스페이스는 알 수 없어서 path만 매칭
	private static Double modifierAmount(AttributeInstance instance, String path) {
		for (AttributeModifier modifier : instance.getModifiers()) {
			if (modifier.id().getPath().equals(path)) {
				return modifier.amount();
			}
		}
		return null;
	}
}
