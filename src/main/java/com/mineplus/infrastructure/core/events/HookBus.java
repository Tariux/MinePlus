package com.mineplus.infrastructure.core.events;

import com.mineplus.infrastructure.core.multiblock.formation.FormationEvent;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEvent;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleEventType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HookBus {

    private final Logger logger;
    private final List<ListenerEntry> listenerEntries;
    private final List<Consumer<FormationEvent>> formationListeners = new CopyOnWriteArrayList<>();

    public HookBus(Logger logger) {
        this.logger = logger;
        this.listenerEntries = new CopyOnWriteArrayList<>();
    }

    /** Registers a listener for formation formed/broken events. */
    public void registerFormationListener(Consumer<FormationEvent> listener) {
        formationListeners.add(listener);
    }

    /** Publishes a formation event to all registered formation listeners. */
    public void publishFormation(FormationEvent event) {
        for (Consumer<FormationEvent> listener : formationListeners) {
            try {
                listener.accept(event);
            } catch (Throwable throwable) {
                logger.log(Level.WARNING, "HookBus: formation listener threw", throwable);
            }
        }
    }

    public void registerLifecycleListener(Consumer<MultiBlockLifecycleEvent> listener) {
        listenerEntries.add(new ListenerEntry(listener, null));
    }

    public void registerLifecycleListener(Consumer<MultiBlockLifecycleEvent> listener, MultiBlockLifecycleEventType... types) {
        if (types.length == 0) {
            listenerEntries.add(new ListenerEntry(listener, null));
        } else {
            listenerEntries.add(new ListenerEntry(listener, Set.of(types)));
        }
    }

    public void publish(MultiBlockLifecycleEvent event) {
        for (ListenerEntry entry : listenerEntries) {
            if (entry.types() != null && !entry.types().contains(event.type())) {
                continue;
            }
            try {
                entry.listener().accept(event);
            } catch (Exception exception) {
                logger.log(Level.WARNING, "Lifecycle listener threw exception for event " + event.type(), exception);
            }
        }
    }

    public List<Consumer<MultiBlockLifecycleEvent>> listeners() {
        List<Consumer<MultiBlockLifecycleEvent>> copy = new ArrayList<>();
        for (ListenerEntry entry : listenerEntries) {
            copy.add(entry.listener());
        }
        return List.copyOf(copy);
    }

    private record ListenerEntry(
            Consumer<MultiBlockLifecycleEvent> listener,
            Set<MultiBlockLifecycleEventType> types
    ) {
    }
}
