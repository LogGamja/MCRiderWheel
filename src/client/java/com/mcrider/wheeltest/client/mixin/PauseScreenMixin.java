package com.mcrider.wheeltest.client.mixin;

import com.mcrider.wheeltest.client.WheelSettingsScreen;
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
	private void mcriderWheelTest$addSettingsButton(CallbackInfo ci) {
		// showsPauseMenu() is false for the bare "saving world"/loading variant of
		// this screen, whose init() returns early without building any menu
		// widgets - skip adding ours too, or it'd be the only thing on an
		// otherwise-empty screen.
		if (!((PauseScreen) (Object) this).showsPauseMenu()) return;

		// Sits just to the right of the top-left corner (not the corner itself,
		// which another mod's own pause-screen button was found occupying at the
		// same size) - still far enough from vanilla's own width/2-centered
		// button column to stay clear of it at any scale.
		this.addRenderableWidget(Button.builder(Component.literal("MCRider Wheel"), b -> {
					assert this.minecraft != null;
					this.minecraft.setScreen(new WheelSettingsScreen(this));
				})
				.bounds(115, 10, 100, 20)
				.build());
	}
}
