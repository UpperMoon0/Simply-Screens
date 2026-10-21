package com.nstut.simplyscreens.api;

import com.nstut.simplyscreens.ScreenNetworkService;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ScreenTargetTest {
    @Test void normalizesTypedAndUntypedNetworks() {
        assertEquals("network/station", ScreenTarget.parse("station"));
        assertEquals("network/station", ScreenTarget.parse("network/station"));
        assertEquals("network/metro.1", ScreenTarget.parse("metro.1"));
    }

    @Test void supportsStandaloneDisplayWithoutAliasingNetwork() {
        UUID physical = UUID.randomUUID();
        assertEquals(ScreenNetworkService.display(physical),
                ScreenTarget.parse("display/" + physical));
        assertNotEquals("network/" + physical, ScreenTarget.parse("display/" + physical));
    }

    @Test void refusesMalformedTargetWithoutCreatingState() {
        for (String invalid : new String[]{"/", "network/", "display/", "display/not-a-uuid", "network/a/b", ""})
            assertThrows(IllegalArgumentException.class, () -> ScreenTarget.parse(invalid), invalid);
        assertThrows(IllegalArgumentException.class, () -> ScreenTarget.parse(null));
    }
}
