package com.nstut.simplyscreens.neoforge.config;

import com.nstut.simplyscreens.Config;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public class ForgeConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue VIEW_DISTANCE = BUILDER
            .comment("The view distance of the screen")
            .defineInRange("viewDistance", Config.VIEW_DISTANCE, 1, 512);

    public static final ModConfigSpec.IntValue SCREEN_TICK_RATE = BUILDER
            .comment("The tick rate of the screen")
            .defineInRange("screenTickRate", Config.SCREEN_TICK_RATE, 1, 200);

    public static final ModConfigSpec.BooleanValue DISABLE_UPLOAD = BUILDER
            .comment("Whether to disable image uploads")
            .define("disableUpload", Config.DISABLE_UPLOAD);

    public static final ModConfigSpec.IntValue MAX_UPLOAD_SIZE = BUILDER
            .comment("The maximum upload size in bytes")
            .defineInRange("maxUploadSize", Config.MAX_UPLOAD_SIZE, Config.MIN_UPLOAD_SIZE, Config.MAX_UPLOAD_SIZE_LIMIT);

    public static final ModConfigSpec SPEC = BUILDER.build();

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