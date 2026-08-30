package com.nstut.simplyscreens.client.testing;

import com.nstut.simplyscreens.client.screens.ImageLoadScreen;
import net.minecraft.client.Minecraft;

/** Tick-driven UI smoke phase for the opt-in live-join client. */
public final class UiSmokeTest {
    private static final int TICKS_PER_STEP = 20;
    private static final int TOTAL_TICKS = TICKS_PER_STEP * 4;

    private enum Phase { JOIN, MOUNT, EXERCISE, CLOSE }

    private static Phase phase = Phase.JOIN;
    private static int ticks;
    private static ImageLoadScreen screen;

    private UiSmokeTest() {
    }

    /** Drives one client tick; returns true once the UI smoke phase finished cleanly. */
    public static boolean tick(Minecraft client) {
        switch (phase) {
            case JOIN:
                if (client.player == null || client.level == null) return false;
                screen = new ImageLoadScreen(client.player.blockPosition());
                client.setScreen(screen);
                phase = Phase.MOUNT;
                ticks = 0;
                return false;
            case MOUNT:
                if (++ticks < TICKS_PER_STEP) return false;
                phase = Phase.EXERCISE;
                ticks = 0;
                return false;
            case EXERCISE:
                ticks++;
                if (ticks % TICKS_PER_STEP == 0 && screen != null) {
                    screen.smokeSelectTab(ticks / TICKS_PER_STEP);
                }
                if (ticks == 3 * TICKS_PER_STEP && client.screen != null) {
                    client.screen.resize(client, client.getWindow().getGuiScaledWidth(),
                            Math.max(60, client.getWindow().getGuiScaledHeight() / 2));
                }
                if (ticks < TOTAL_TICKS) return false;
                phase = Phase.CLOSE;
                return false;
            case CLOSE:
            default:
                client.setScreen(null);
                screen = null;
                phase = Phase.JOIN;
                ticks = 0;
                return true;
        }
    }
}
