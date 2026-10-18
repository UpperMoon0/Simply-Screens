package com.nstut.simplyscreens;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ScreenPlayerInputTest {
    @Test void uuidWorksOfflineAndMalformedUuidCannotBecomeAPlayerName() {
        UUID id = UUID.randomUUID();
        assertEquals(id, ScreenPlayerInput.resolve(" " + id + " ", name -> { fail("UUID must not require lookup"); return null; }));
        assertNull(ScreenPlayerInput.resolve("1-1-1-1-1", name -> { fail("Invalid UUID must not be looked up"); return null; }));
        assertNull(ScreenPlayerInput.resolve("bad player", name -> { fail("Invalid name"); return null; }));
    }
    @Test void namesResolveOnlyThroughOnlineRoster() {
        UUID id = UUID.randomUUID();
        assertEquals(id, ScreenPlayerInput.resolve(" Player_1 ", name -> name.equals("Player_1") ? id : null));
        assertNull(ScreenPlayerInput.resolve("Offline", name -> null));
    }
    @Test void snapshotFiltersHiddenRosterNamesAndFitsPacketAtFullCapacity() {
        UUID owner = UUID.randomUUID(), hidden = UUID.randomUUID();
        Set<UUID> trusted = new HashSet<>();
        Map<UUID, String> names = new HashMap<>();
        names.put(owner, "Owner"); names.put(hidden, "Hidden");
        for (int i = 0; i < 64; i++) { UUID id = UUID.randomUUID(); trusted.add(id); names.put(id, "PlayerName" + i); }
        var state = new ScreenNetworkService.State(owner, trusted, ScreenNetworkService.Policy.TRUSTED,
                ScreenNetworkService.Policy.OWNER_ONLY, null, "STATIC", 0, 0, 1);
        var snapshot = new ScreenAccessSnapshot("network/" + "x".repeat(64), state, true, true, false, names);
        assertEquals(65, snapshot.playerNames().size());
        assertFalse(snapshot.playerNames().containsKey(hidden));
        assertTrue(snapshot.toJson().length() < 8192, "Full roster must fit the access packet");
        assertEquals(snapshot.playerNames(), ScreenAccessSnapshot.fromJson(snapshot.toJson()).playerNames());
        var visitor = new ScreenAccessSnapshot("network/test", state, false, true, false, names);
        assertEquals(Map.of(owner, "Owner"), visitor.playerNames());
    }
}
