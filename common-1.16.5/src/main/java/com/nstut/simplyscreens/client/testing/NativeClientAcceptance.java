package com.nstut.simplyscreens.client.testing;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.testing.LiveJoinTestProtocol;
import net.minecraft.client.Minecraft;
/** Opt-in existing live-join/UI contract, never active during ordinary gameplay. */
public final class NativeClientAcceptance {
    private static String lastWaitingState;
    private NativeClientAcceptance() {}
    public static void initialize() {
        LiveJoinTestProtocol.startProbe();
        me.shedaniel.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(NativeClientAcceptance::tick);
    }
    private static void tick(Minecraft client) {
        if (!LiveJoinTestProtocol.isEnabled()) return;
        if (client.player == null || client.level == null) {
            String state = "screen=" + (client.screen == null ? "none" : client.screen.getClass().getName())
                    + ", overlay=" + (client.getOverlay() == null ? "none" : client.getOverlay().getClass().getName())
                    + ", multiplayerAllowed=" + client.allowsMultiplayer();
            if (!state.equals(lastWaitingState)) {
                lastWaitingState = state;
                SimplyScreens.LOGGER.info("Native join probe waiting: {}", state);
            }
            return;
        }
        if (!UiSmokeTest.tick(client)) return;
        LiveJoinTestProtocol.markCompleted();
        if (LiveJoinTestProtocol.passed() && LiveJoinTestProtocol.markReported()) {
            SimplyScreens.LOGGER.info(LiveJoinTestProtocol.PASS_MARKER);
            LiveJoinTestProtocol.stopClient(client::stop);
        }
    }
}
