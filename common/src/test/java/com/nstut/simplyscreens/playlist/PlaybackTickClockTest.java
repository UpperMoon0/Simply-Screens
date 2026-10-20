package com.nstut.simplyscreens.playlist;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackTickClockTest {
    @Test void pausedWallTimeDoesNotConsumeTheFirstSlide() {
        var clock = new PlaybackTickClock();
        var service = new ScreenPlaylistService();
        UUID owner = UUID.randomUUID(), image = UUID.randomUUID();
        UUID list = service.create(owner, "Slideshow", 0);
        assertTrue(service.replaceSlides(owner, list, List.of(new PlaylistSlide(image, 10_000)), 1));
        clock.advance(100);
        assertTrue(service.play("network/test", owner, list, 0));
        // A paused integrated server does not advance its tick counter, however long the menu is open.
        assertEquals(0, clock.advance(100));
        service.tick(clock.advance(101));
        assertEquals(image, service.snapshot("network/test").currentImage());
        assertEquals(50, service.snapshot("network/test").offsetMillis());
    }

    @Test void resetAndTickCounterRolloverDoNotSkipPlayback() {
        var clock = new PlaybackTickClock();
        assertEquals(0, clock.advance(Integer.MAX_VALUE));
        assertEquals(50, clock.advance(Integer.MIN_VALUE));
        assertEquals(100, clock.advance(Integer.MIN_VALUE + 2));
        clock.reset();
        assertEquals(0, clock.advance(0));
    }
}
