package com.mcrider.mcriderwheel.client.mixin;

import com.mcrider.mcriderwheel.client.RiddenVehicle;
import com.mcrider.mcriderwheel.client.ffb.WheelForceFeedback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SoundManager.addListener()는 로컬 재생 볼륨이 0보다 클 때만 알려주므로
// 너무 멀거나 조용해서 안 들리는 소리는 놓친다. 패킷을 직접 후킹하면 거리/볼륨과 무관하게 잡힌다
@Mixin(ClientPacketListener.class)
public abstract class SoundPacketMixin {
	// 좀비가 문 부수는 소리를 MCRider의 충돌 신호 대용으로 사용 중
	private static final ResourceLocation ATTACK_DOOR_SOUND = ResourceLocation.withDefaultNamespace("entity.zombie.attack_wooden_door");

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void mcriderWheel$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
		handle(packet.getSound());
	}

	@Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
	private void mcriderWheel$onSoundEntityEvent(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		handle(packet.getSound());
	}

	// 스레드 체크가 리소스 읽기보다 먼저 실행되도록 resolve된 값 대신 raw Holder를 받는다
	private static void handle(Holder<SoundEvent> sound) {
		Minecraft client = Minecraft.getInstance();
		// 이 HEAD 인젝션은 netty 스레드와 메인 스레드에서 두 번 불리므로
		// 네이티브 호출 충돌을 막기 위해 메인 스레드가 아니면 무시한다
		if (!client.isSameThread()) return;

		ResourceLocation soundLocation;
		try {
			soundLocation = sound.value().location();
		} catch (Throwable t) {
			// 예외가 새어나가면 접속이 끊기므로 여기서 막는다
			return;
		}
		if (!ATTACK_DOOR_SOUND.equals(soundLocation)) return;

		// 직접 타는 경우와 관전 중인 경우를 모두 커버
		if (RiddenVehicle.get(client) == null) return;

		WheelForceFeedback.pulse(0.2f, 150);
	}
}
