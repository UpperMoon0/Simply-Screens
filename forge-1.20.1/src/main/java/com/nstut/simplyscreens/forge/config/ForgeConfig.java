package com.nstut.simplyscreens.forge.config;

import com.nstut.simplyscreens.Config;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class ForgeConfig {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue VIEW_DISTANCE = BUILDER
            .comment("The view distance of the screen")
            .defineInRange("viewDistance", Config.VIEW_DISTANCE, 1, 512);

    public static final ForgeConfigSpec.IntValue SCREEN_TICK_RATE = BUILDER
            .comment("The tick rate of the screen")
            .defineInRange("screenTickRate", Config.SCREEN_TICK_RATE, 1, 200);

    public static final ForgeConfigSpec.BooleanValue DISABLE_UPLOAD = BUILDER
            .comment("Whether to disable image uploads")
            .define("disableUpload", Config.DISABLE_UPLOAD);

    public static final ForgeConfigSpec.IntValue MAX_UPLOAD_SIZE = BUILDER
            .comment("The maximum upload size in bytes")
            .defineInRange("maxUploadSize", Config.MAX_UPLOAD_SIZE, Config.MIN_UPLOAD_SIZE, Config.MAX_UPLOAD_SIZE_LIMIT);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    @SubscribeEvent
    public static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            Config.VIEW_DISTANCE = VIEW_DISTANCE.get();
            Config.SCREEN_TICK_RATE = SCREEN_TICK_RATE.get();
            Config.DISABLE_UPLOAD = DISABLE_UPLOAD.get();
            Config.MAX_UPLOAD_SIZE = MAX_UPLOAD_SIZE.get();
        }
    }
}