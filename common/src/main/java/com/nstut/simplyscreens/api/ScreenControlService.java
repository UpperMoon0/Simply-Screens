package com.nstut.simplyscreens.api;

import com.nstut.simplyscreens.ScreenNetworkService;
import com.nstut.simplyscreens.ScreenNetworkService.Actor;
import com.nstut.simplyscreens.ScreenNetworkService.Permission;
import com.nstut.simplyscreens.ScreenNetworkService.Policy;
import com.nstut.simplyscreens.playlist.NamedPlaylist;
import com.nstut.simplyscreens.playlist.PlaybackSnapshot;
import com.nstut.simplyscreens.playlist.ScreenPlaylistService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Version-neutral control gateway. Native ScreenApi adapters derive actors from actual players,
 * and verify media ownership before calling this gateway. Never expose Actor construction in a
 * player-facing packet or accept an operator flag from a client.
 */
public final class ScreenControlService {
    public record Status(String key, UUID owner, UUID image, Policy viewPolicy, Policy controlPolicy,
                         long networkRevision, PlaybackSnapshot.Status playbackStatus,
                         int slideIndex, long offsetMillis, long playbackRevision, boolean canControl) { }

    private final ScreenNetworkService networks;
    private final ScreenPlaylistService playlists;

    public ScreenControlService(ScreenNetworkService networks, ScreenPlaylistService playlists) {
        this.networks = Objects.requireNonNull(networks);
        this.playlists = playlists;
    }

    /** Missing or inaccessible networks are indistinguishable. VIEW controls content visibility. */
    public Status status(String key, Actor actor) {
        if (key == null) return null;
        Actor effective = actor == null ? Actor.automation() : actor;
        var state = networks.snapshot(key);
        if (state == null) return null;
        boolean view = networks.allowed(key, effective, Permission.VIEW);
        boolean control = networks.allowed(key, effective, Permission.CONTROL);
        boolean manage = networks.allowed(key, effective, Permission.MANAGE);
        if (!view && !control && !manage) return null;
        var playback = playlists == null ? PlaybackSnapshot.EMPTY : playlists.snapshot(key);
        return new Status(key, manage ? state.owner() : null, view ? state.image() : null,
                state.view(), state.control(), state.revision(),
                view ? playback.status() : PlaybackSnapshot.Status.STOPPED,
                view ? playback.slideIndex() : -1, view ? playback.offsetMillis() : 0,
                playback.revision(), control);
    }

    public List<Status> list(Actor actor) {
        List<Status> result = new ArrayList<>();
        for (String key : networks.keys()) {
            Status snapshot = status(key, actor);
            if (snapshot != null) result.add(snapshot);
        }
        result.sort(Comparator.comparing(Status::key));
        return List.copyOf(result);
    }

    /** Refuses inaccessible assets even when the network itself permits control. */
    public boolean select(Actor actor, String key, UUID image, Predicate<UUID> mayReadImage) {
        Actor effective = actor == null ? Actor.automation() : actor;
        var state = key == null ? null : networks.snapshot(key);
        if (state == null || !networks.allowed(key, effective, Permission.CONTROL)
                || image != null && (mayReadImage == null || !mayReadImage.test(image))) return false;
        if (!networks.select(key, effective, image, state.revision())) return false;
        if (playlists != null) playlists.removeNetwork(key);
        return true;
    }

    /** The caller must own the saved playlist, unless it is a real server operator. */
    public boolean play(Actor actor, String key, UUID owner, UUID playlistId,
                        Predicate<UUID> mayReadImage) {
        Actor effective = actor == null ? Actor.automation() : actor;
        if (playlists == null || key == null || owner == null || playlistId == null
                || !networks.allowed(key, effective, Permission.CONTROL)
                || (!effective.operator() && !owner.equals(effective.id()))) return false;
        NamedPlaylist saved = playlists.library(owner).find(playlistId);
        if (saved == null || saved.slides().isEmpty() || mayReadImage == null
                || !saved.slides().stream().allMatch(s -> mayReadImage.test(s.image()))) return false;
        return playlists.play(key, owner, playlistId, playlists.snapshot(key).revision());
    }

    /** Operates on the persisted network session, including unloaded and cross-dimensional displays. */
    public boolean transport(Actor actor, String key, String action) {
        Actor effective = actor == null ? Actor.automation() : actor;
        if (playlists == null || key == null || action == null
                || !List.of("PAUSE", "RESUME", "STOP", "NEXT", "PREVIOUS", "RESTART").contains(action)
                || !networks.allowed(key, effective, Permission.CONTROL)) return false;
        return playlists.command(key, action, playlists.snapshot(key).revision());
    }

    public PlaybackSnapshot playback(String key, Actor actor) {
        return key != null && playlists != null && networks.allowed(key,
                actor == null ? Actor.automation() : actor, Permission.MANAGE)
                ? playlists.snapshot(key) : PlaybackSnapshot.EMPTY;
    }
}
