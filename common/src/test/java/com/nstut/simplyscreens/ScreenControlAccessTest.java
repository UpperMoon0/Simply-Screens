package com.nstut.simplyscreens;

import com.nstut.simplyscreens.ScreenNetworkService.*;
import com.nstut.simplyscreens.api.*;
import com.nstut.simplyscreens.playlist.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScreenControlAccessTest {
    @Test void inactiveLinkedDisplayCannotExposeOrMutateItsDormantPrivateContent() {
        var networks = new ScreenNetworkService();
        var playlists = new ScreenPlaylistService();
        var api = new ScreenControlService(networks, playlists);
        UUID owner = UUID.randomUUID(), display = UUID.randomUUID(), privateImage = UUID.randomUUID();
        String key = ScreenNetworkService.network("public"), dormant = ScreenNetworkService.display(display);
        var actor = new Actor(owner, false);
        assertTrue(networks.createOwned(key, owner, null));
        assertTrue(networks.createOwned(dormant, owner, privateImage));
        UUID saved = playlists.create(owner, "Dormant", 0);
        assertTrue(playlists.replaceSlides(owner, saved, List.of(new PlaylistSlide(privateImage,1000)),1));
        assertTrue(playlists.play(dormant, owner, saved,0));
        networks.updateDisplay(display, "public", false, true);
        assertTrue(networks.access(key, actor, 0, "CONTROL", "PUBLIC"));
        assertNull(api.status(dormant, null), "This physical display currently uses network content, not its private override");
        assertTrue(api.list(null).stream().noneMatch(s -> s.key().equals(dormant)));
        assertFalse(api.select(null, dormant, null, id -> true));
        assertFalse(api.play(actor, dormant, owner, saved, id -> true));
        assertFalse(api.transport(actor, dormant, "STOP"));
        assertEquals(PlaybackSnapshot.EMPTY, api.playback(dormant, actor));
        assertEquals(privateImage, networks.snapshot(dormant).image());
        assertNotNull(api.status(key, null));
    }
    @Test void linkedOverrideFollowsTransferredNetworkAclForEveryApiOperation() {
        var networks = new ScreenNetworkService();
        var playlists = new ScreenPlaylistService();
        var api = new ScreenControlService(networks, playlists);
        UUID former = UUID.randomUUID(), owner = UUID.randomUUID(), friend = UUID.randomUUID();
        UUID display = UUID.randomUUID(), image = UUID.randomUUID();
        Actor oldActor = new Actor(former, false), current = new Actor(owner, false), trusted = new Actor(friend, false);
        String key = ScreenNetworkService.network("linked"), physical = ScreenNetworkService.display(display);
        assertTrue(networks.createOwned(key, former, null));
        assertTrue(networks.createOwned(physical, former, image));
        networks.updateDisplay(display, "linked", true, true);
        assertTrue(networks.access(key, oldActor, 0, "VIEW", "OWNER_ONLY"));
        assertTrue(networks.access(key, oldActor, 1, "TRANSFER", owner.toString()));
        UUID list = playlists.create(owner, "Private", 0);
        assertTrue(playlists.replaceSlides(owner, list, List.of(new PlaylistSlide(image, 1000)), 1));
        assertNull(api.status(physical, oldActor));
        assertTrue(api.list(oldActor).isEmpty());
        assertFalse(api.select(oldActor, physical, null, id -> true));
        assertFalse(api.play(oldActor, physical, former, list, id -> true));
        assertFalse(api.transport(oldActor, physical, "STOP"));
        assertEquals(PlaybackSnapshot.EMPTY, api.playback(physical, oldActor));
        assertEquals(owner, api.status(physical, current).owner());
        assertTrue(api.select(current, physical, image, id -> true));
        assertTrue(api.play(current, physical, owner, list, id -> true));
        assertTrue(api.transport(current, physical, "PAUSE"));
        assertEquals(PlaybackSnapshot.Status.PAUSED, api.playback(physical, current).status());
        assertTrue(networks.access(key, current, 2, "TRUST", friend.toString()));
        assertTrue(networks.access(key, current, 3, "VIEW", "TRUSTED"));
        assertTrue(networks.access(key, current, 4, "CONTROL", "TRUSTED"));
        assertTrue(api.status(physical, trusted).canControl());
        assertEquals(image, api.status(physical, trusted).image());
        assertTrue(api.transport(trusted, physical, "RESUME"));
        assertTrue(api.select(trusted, physical, image, id -> true));
        assertTrue(networks.access(key, current, 5, "VIEW", "OWNER_ONLY"));
        assertTrue(networks.access(key, current, 6, "CONTROL", "PUBLIC"));
        assertNull(api.status(physical, null).image());
        assertTrue(api.select(null, physical, null, id -> true));
        assertNull(networks.snapshot(physical).image());
        assertNull(networks.snapshot(key).image());
    }
}
