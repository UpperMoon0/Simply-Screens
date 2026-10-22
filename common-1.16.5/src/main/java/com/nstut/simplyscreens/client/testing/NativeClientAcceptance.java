package com.nstut.simplyscreens.client.testing;
import com.nstut.simplyscreens.SimplyScreens;
import com.nstut.simplyscreens.testing.LiveJoinTestProtocol;
import net.minecraft.client.Minecraft;
/** Opt-in existing live-join/UI contract, never active during ordinary gameplay. */
public final class NativeClientAcceptance {
    private static String lastWaitingState;
    private static boolean uiCompleted, integratedReported;
    private NativeClientAcceptance() {}
    public static void initialize() {
        LiveJoinTestProtocol.startProbe();
        me.shedaniel.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(NativeClientAcceptance::tick);
    }
    private static void tick(Minecraft client) {
        boolean integrated = Boolean.getBoolean("simplyscreens.integratedAcceptance");
        if (!LiveJoinTestProtocol.isEnabled() && !integrated) return;
        LegacyMediaClientProbe.validateActiveLifecycle(client);
        if (integrated) VanillaSingleplayerDriver.tick(client);
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
        if (integrated && (!client.isLocalServer() || client.getSingleplayerServer() == null
                || client.getSingleplayerServer().isDedicatedServer()))
            throw new IllegalStateException("SIMPLYSCREENS_INTEGRATED_FAIL expected native integrated server");
        if (!uiCompleted) {
            if (!UiSmokeTest.tick(client)) return;
            uiCompleted = true;
            if (integrated) SimplyScreens.LOGGER.info("SIMPLYSCREENS_INTEGRATED_UI_PASS tabs=true resize=true");
        }
        if (!LegacyMediaClientProbe.tick(client)) return;
        if (integrated) {
            if (!Boolean.getBoolean("simplyscreens.mediaSmokeTest") || !"1.8".equals(System.getProperty("java.specification.version")))
                throw new IllegalStateException("SIMPLYSCREENS_INTEGRATED_FAIL media probe and actual Java 8 required");
            if (!integratedReported) {
                integratedReported = true;
                SimplyScreens.LOGGER.info("SIMPLYSCREENS_INTEGRATED_CLIENT_PASS javaMajor=8 dedicated=false multiClient=false");
                client.stop();
            }
            return;
        }
        LiveJoinTestProtocol.markCompleted();
        if (LiveJoinTestProtocol.passed() && LiveJoinTestProtocol.markReported()) {
            SimplyScreens.LOGGER.info(LiveJoinTestProtocol.PASS_MARKER);
            LiveJoinTestProtocol.stopClient(client::stop);
        }
    }
}
