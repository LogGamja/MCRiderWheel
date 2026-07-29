package com.mcrider.mcriderwheel.client.mixin;

import com.mcrider.mcriderwheel.client.WheelSettingsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("RETURN"))
	private void mcriderWheel$addSettingsButton(CallbackInfo ci) {
		// 저장/로딩 중 화면은 위젯이 없어 여기서 건너뛴다
		if (!((PauseScreen) (Object) this).showsPauseMenu()) return;

		// 좌상단 모서리는 다른 모드 버튼과 겹쳐서 약간 오른쪽으로 배치
		this.addRenderableWidget(Button.builder(Component.literal("레이싱 휠 설정"), b -> {
					assert this.minecraft != null;
					this.minecraft.setScreen(new WheelSettingsScreen(this));
				})
				.bounds(115, 10, 100, 20)
				.build());
	}
}
