package com.nstut.simplyscreens.compat;
import me.shedaniel.architectury.networking.NetworkManager;
import me.shedaniel.architectury.utils.Env;
import net.minecraft.resources.ResourceLocation;
/** Reject direction mismatches before decoding or touching client-only handlers. */
public final class DirectionalReceivers {
    private DirectionalReceivers() {}
    public static void register(NetworkManager.Side side, ResourceLocation id, NetworkManager.NetworkReceiver receiver) {
        final Env expected = side == NetworkManager.c2s() ? Env.SERVER : Env.CLIENT;
        NetworkManager.registerReceiver(side, id, (buffer, context) -> {
            if (context.getEnvironment() == expected) receiver.receive(buffer, context);
        });
    }
}
