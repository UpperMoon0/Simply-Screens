package com.nstut.simplyscreens;
import com.google.gson.Gson;
import java.util.Map;
import java.util.UUID;
/** A bounded, redacted snapshot delivered only in response to a nearby GUI request. */
public record ScreenAccessSnapshot(String key, ScreenNetworkService.State state, boolean manage, boolean success,
                                   boolean localOverride, Map<UUID, String> playerNames, long requestId) {
    public ScreenAccessSnapshot(String key, ScreenNetworkService.State state, boolean manage, boolean success, boolean localOverride) {
        this(key, state, manage, success, localOverride, Map.of(), 0);
    }
    public ScreenAccessSnapshot(String key, ScreenNetworkService.State state, boolean manage, boolean success,
                                boolean localOverride, Map<UUID, String> playerNames) {
        this(key, state, manage, success, localOverride, playerNames, 0);
    }
    public ScreenAccessSnapshot {
        var safe = new java.util.HashMap<UUID, String>();
        if (playerNames != null) playerNames.forEach((id, name) -> {
            if (id != null && name != null && name.matches("[A-Za-z0-9_]{1,16}")
                    && (id.equals(state.owner()) || manage && state.trusted().contains(id))) safe.put(id, name);
        });
        playerNames = Map.copyOf(safe);
    }
    public String playerName(UUID id) { return playerNames.getOrDefault(id, id.toString()); }
    public String toJson() { return new Gson().toJson(this); }
    public static ScreenAccessSnapshot fromJson(String json) { return new Gson().fromJson(json, ScreenAccessSnapshot.class); }
}
