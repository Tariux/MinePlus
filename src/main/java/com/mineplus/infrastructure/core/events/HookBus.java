package com.mineplus.infrastructure.core.events;

import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class HookBus {

    private final List<Consumer<MultiBlockLifecycleEvent>> lifecycleListeners;

    public HookBus() {
        this.lifecycleListeners = new CopyOnWriteArrayList<>();
    }

    public void registerLifecycleListener(Consumer<MultiBlockLifecycleEvent> listener) {
        lifecycleListeners.add(listener);
    }

    public void publish(MultiBlockLifecycleEvent event) {
        for (Consumer<MultiBlockLifecycleEvent> listener : lifecycleListeners) {
            listener.accept(event);
        }
    }

    public List<Consumer<MultiBlockLifecycleEvent>> listeners() {
        return List.copyOf(new ArrayList<>(lifecycleListeners));
    }
}
