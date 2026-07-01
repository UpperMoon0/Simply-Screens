package com.nstut.simplyscreens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Version-neutral index of screen positions grouped by world and user-defined ID. */
public final class ScreenLinkIndex<L, P> {
    private final Map<L, Map<P, String>> screensByLevel = new ConcurrentHashMap<>();

    public void register(L level, P position, String screenId) {
        if (level == null || position == null || screenId == null || screenId.isEmpty()) return;
        screensByLevel.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>()).put(position, screenId);
    }

    public void unregister(L level, P position) {
        Map<P, String> levelScreens = screensByLevel.get(level);
        if (levelScreens == null) return;
        levelScreens.remove(position);
        if (levelScreens.isEmpty()) screensByLevel.remove(level);
    }

    public void update(L level, P position, String newScreenId) {
        unregister(level, position);
        register(level, position, newScreenId);
    }

    public String getScreenId(L level, P position) {
        Map<P, String> levelScreens = screensByLevel.get(level);
        return levelScreens == null ? null : levelScreens.get(position);
    }

    public List<P> getPositions(L level, String screenId) {
        if (screenId == null || screenId.isEmpty()) return List.of();
        Map<P, String> levelScreens = screensByLevel.get(level);
        if (levelScreens == null) return List.of();
        List<P> positions = new ArrayList<>();
        levelScreens.forEach((position, id) -> {
            if (screenId.equals(id)) positions.add(position);
        });
        return List.copyOf(positions);
    }

    public void clear(L level) {
        screensByLevel.remove(level);
    }
}
