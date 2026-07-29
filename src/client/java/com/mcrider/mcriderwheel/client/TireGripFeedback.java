package com.mcrider.mcriderwheel.client;

import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
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
 * "state-drift" flag is what disambiguates the two - while it's set, this
 * always feeds the full-strength vibration magnitude regardless of what the
 * XP bar reads, and also reports the wheel as broken loose (see
 * setTiresBrokenLoose()) so WheelForceFeedback drops the centering pull out
 * from under that vibration instead of pulling against it - a real slide
 * gives no restoring torque back through the wheel. The soft-lock wall stays
 * though: it's a mechanical end-stop on the rig itself, not a grip effect.
 */
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

		// XP below stays read from client.player regardless of whose vehicle this
		// is: the server mirrors whatever's being spectated onto the spectator's
		// own XP bar, so client.player's is already the right gauge to read either way.
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

		// A real slide is always fed through as the gauge reading 100%, not
		// whatever the reset briefly leaves on the XP bar - state-drift is
		// what actually says the tires are broken loose, the bar doesn't.
		// WheelForceFeedback keeps rendering this magnitude, just with no
		// centering pull underneath it, while tiresBrokenLoose is set - see
		// its own comment.
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
