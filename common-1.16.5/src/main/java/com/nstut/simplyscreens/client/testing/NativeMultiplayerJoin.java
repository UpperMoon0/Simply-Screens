package com.nstut.simplyscreens.client.testing;

import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.testing.LiveJoinTestProtocol;
import com.nstut.simplyscreens.testing.NativeMultiplayerJoinGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Opt-in real native connection after initial resources finish; never dismisses error/account screens. */
public final class NativeMultiplayerJoin {
    private static final NativeMultiplayerJoinGate GATE = new NativeMultiplayerJoinGate();
    private NativeMultiplayerJoin() { }
    public static void tick(Minecraft client) {
        if (!LiveJoinTestProtocol.isEnabled() || Boolean.getBoolean("simplyscreens.integratedAcceptance")
                || !Boolean.getBoolean("simplyscreens.connectAfterInitialReload")) return;
        if (!GATE.claim(client.screen instanceof TitleScreen, client.getOverlay() != null, client.allowsMultiplayer())) return;
        SimplyScreens.LOGGER.info("SIMPLYSCREENS_NATIVE_JOIN_START initialResourcesReady=true multiplayerAllowed=true");
        client.setScreen(new ConnectScreen(client.screen, client, "127.0.0.1", 25575));
    }
}
