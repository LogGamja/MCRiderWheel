package com.mcrider.mcriderwheel.client.mixin;

import com.mcrider.mcriderwheel.client.WheelDrivingControl;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla detects double-tap-forward-to-sprint with a short "trigger
 * window" (sprintTriggerTime): release forward, then press it again within
 * ~7 ticks, and that counts as a double tap. The PWM throttle fine-control
 * in {@link WheelDrivingControl} taps the forward key on/off many times a
 * second at partial pedal, which looks exactly like rapid double-tapping
 * to that heuristic - so even a light touch on the pedal would start
 * sprinting. Zeroing the trigger window every tick while the pedal is
 * actively being PWM-modulated keeps that window from ever arming, without
 * touching real keyboard double-tap sprinting when the pedal is untouched.
 */
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
