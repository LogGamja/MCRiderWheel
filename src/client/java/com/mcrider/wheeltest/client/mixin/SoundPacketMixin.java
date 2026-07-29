package com.mcrider.wheeltest.client.mixin;

import com.mcrider.wheeltest.client.RiddenVehicle;
import com.mcrider.wheeltest.client.ffb.WheelForceFeedback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SoundManager.addListener() (the non-mixin way to observe sound playback)
 * only notifies listeners after SoundEngine.play() has already computed a
 * non-zero local playback volume - a sound the server broadcasts but that's
 * too quiet or too far away to actually be audible locally never reaches
 * it. Hooking the raw sound packets instead fires on every server-side
 * occurrence of the event regardless of local distance/volume.
 */
@Mixin(ClientPacketListener.class)
public abstract class SoundPacketMixin {
	// Zombies breaking down a wooden door is MCRider's stand-in impact cue
	// (no dedicated collision sound exists yet) - fire a kick whenever it
	// plays while the player is riding, or spectating someone who is.
	private static final ResourceLocation ATTACK_DOOR_SOUND = ResourceLocation.withDefaultNamespace("entity.zombie.attack_wooden_door");

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void mcriderWheelTest$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
		handle(packet.getSound().value().location());
	}

	@Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
	private void mcriderWheelTest$onSoundEntityEvent(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		handle(packet.getSound().value().location());
	}

	private static void handle(ResourceLocation soundLocation) {
		if (!ATTACK_DOOR_SOUND.equals(soundLocation)) return;

		Minecraft client = Minecraft.getInstance();
		// Both handlers' real bodies open with PacketUtils.ensureRunningOnSameThread(),
		// which - when we're not already on the main thread - reschedules the whole
		// packet handler onto it and aborts the current (netty-thread) call via a
		// thrown RunningOnDifferentThreadException. Since that means this HEAD
		// injection actually fires twice (once off-thread, once for real), skip the
		// off-thread call here rather than acting on it: WheelForceFeedback's
		// SDL_Haptic calls all assume the client tick thread, and letting the netty
		// thread call pulse() concurrently with tick()'s set_force()/stop() on the
		// same handle is exactly the kind of native-side race that crashes the JVM.
		if (!client.isSameThread()) return;

		// A player riding directly has cameraEntity == the player, while
		// spectating another entity (e.g. via the player list) points
		// cameraEntity at that target instead - checking its vehicle covers
		// both "I'm riding" and "I'm watching someone who's riding" alike.
		if (RiddenVehicle.get(client) == null) return;

		WheelForceFeedback.pulse(0.2f, 150);
	}
}
