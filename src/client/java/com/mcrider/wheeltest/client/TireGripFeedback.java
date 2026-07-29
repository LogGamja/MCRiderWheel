package com.mcrider.wheeltest.client;

import com.mcrider.wheeltest.client.ffb.WheelForceFeedback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * MCRider's "engine 1006" kart doesn't expose grip through any MCRider API -
 * it piggybacks on the vehicle's vanilla ARMOR attribute, using named
 * AttributeModifiers on it as ad-hoc data fields ("data-engine-real" for the
 * engine id, "state-drift" as a 0/1 flag) rather than the attribute's own
 * value. The actual grip gauge is the player's own XP progress bar (0..100%).
 *
 * Reading the XP bar alone can't tell a real slide apart from ordinary grip:
 * when the tires actually break loose, engine 1006 resets the gauge down to
 * around 20%, which looks identical to "gripping fine at 20%". The
 * "state-drift" flag is what disambiguates the two, so a real slide always
 * forces full-strength vibration regardless of what the XP bar reads.
 */
public final class TireGripFeedback {
	private static final int GRIP_ENGINE_ID = 1006;
	// XP-bar fraction where the grip-loss vibration starts ramping in; below
	// this, grip is assumed fine and nothing is fed back.
	private static final float GRIP_RAMP_START = 0.25f;
	// Caps how strong the ramp gets by the time the gauge hits 100% (1f would
	// mean full-strength, matching isDrifting) - lower this to flatten the
	// ramp's slope without moving where it starts.
	private static final float GRIP_RAMP_MAX_MAGNITUDE = 0.2f;
	// Vibration strength while state-drift is active, independent of the ramp above.
	private static final float DRIFT_MAGNITUDE = 0.2f;

	private TireGripFeedback() {
	}

	public static void tick(Minecraft client) {
		if (client.player == null) {
			WheelForceFeedback.setGripVibrationMagnitude(0f);
			return;
		}

		// XP below stays read from client.player regardless of whose vehicle this
		// is: the server mirrors whatever's being spectated onto the spectator's
		// own XP bar, so client.player's is already the right gauge to read either way.
		Entity vehicle = RiddenVehicle.get(client);
		if (!(vehicle instanceof LivingEntity livingVehicle)) {
			WheelForceFeedback.setGripVibrationMagnitude(0f);
			return;
		}

		AttributeInstance armor = livingVehicle.getAttribute(Attributes.ARMOR);
		if (armor == null) {
			WheelForceFeedback.setGripVibrationMagnitude(0f);
			return;
		}

		Double engineId = modifierAmount(armor, "data-engine-real");
		if (engineId == null || Math.round(engineId) != GRIP_ENGINE_ID) {
			WheelForceFeedback.setGripVibrationMagnitude(0f);
			return;
		}

		Double drifting = modifierAmount(armor, "state-drift");
		boolean isDrifting = drifting != null && drifting >= 0.5;

		float magnitude;
		if (isDrifting) {
			magnitude = DRIFT_MAGNITUDE;
		} else {
			float gripProgress = client.player.experienceProgress;
			magnitude = gripProgress > GRIP_RAMP_START
					? (gripProgress - GRIP_RAMP_START) / (1f - GRIP_RAMP_START) * GRIP_RAMP_MAX_MAGNITUDE
					: 0f;
		}

		WheelForceFeedback.setGripVibrationMagnitude(magnitude);
	}

	/** Matched by modifier id *path* only (namespace-agnostic) since MCRider's exact namespace for these isn't known here. */
	private static Double modifierAmount(AttributeInstance instance, String path) {
		for (AttributeModifier modifier : instance.getModifiers()) {
			if (modifier.id().getPath().equals(path)) {
				return modifier.amount();
			}
		}
		return null;
	}
}
