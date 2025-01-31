package com.nstut.simplyscreens;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = SimplyScreens.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue VIEW_DISTANCE =
            BUILDER.comment("Set the view distance for screen blocks (default: 64).")
                    .defineInRange("screenBlock.viewDistance", 64, 1, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.IntValue SCREEN_TICK_RATE =
            BUILDER.comment("Set the tick rate for screen blocks (default: 100).")
                    .defineInRange("screenBlock.tickRate", 100, 1, Integer.MAX_VALUE);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
    }
}
