package com.mcrider.wheeltest;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WheelTestMod implements ModInitializer {
	public static final String MOD_ID = "mcrider_wheel_test";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("MCRider Wheel Test loaded");
	}
}
