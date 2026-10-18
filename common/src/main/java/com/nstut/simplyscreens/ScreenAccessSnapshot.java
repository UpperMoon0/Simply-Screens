package com.nstut.simplyscreens;
import com.google.gson.Gson;
/** A bounded, redacted snapshot delivered only in response to a nearby GUI request. */
public record ScreenAccessSnapshot(String key, ScreenNetworkService.State state, boolean manage, boolean success, boolean localOverride) {
    public String toJson() { return new Gson().toJson(this); }
    public static ScreenAccessSnapshot fromJson(String json) { return new Gson().fromJson(json, ScreenAccessSnapshot.class); }
}
