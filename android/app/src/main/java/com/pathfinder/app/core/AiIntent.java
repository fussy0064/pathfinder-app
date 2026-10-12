package com.pathfinder.app.core;

import java.util.List;

public final class AiIntent {
    public final List<String> kinds;
    public final List<String> names;

    public AiIntent(List<String> kinds, List<String> names) {
        this.kinds = kinds;
        this.names = names;
    }

    public boolean isEmpty() {
        return kinds.isEmpty() && names.isEmpty();
    }
}
