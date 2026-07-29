package com.mcrider.mcriderwheel.client.mixin;

import com.mcrider.mcriderwheel.client.WheelDrivingControl;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// PWM으로 페달을 빠르게 여닫으면 바닐라의 더블탭 스프린트 감지가 오작동하므로
// 페달이 실제로 조작되는 동안은 트리거 윈도우를 매 틱 0으로 초기화한다
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintMixin {
	@Shadow
	protected int sprintTriggerTime;

	@Inject(method = "aiStep", at = @At("HEAD"))
	private void mcriderWheel$suppressDoubleTapSprint(CallbackInfo ci) {
		if (WheelDrivingControl.isSuppressingAutoSprintTrigger()) {
			this.sprintTriggerTime = 0;
		}
	}
}
