package com.mineplus.infrastructure.core.minigame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Instance lifecycle manager (mega-plan D3 / Phase D): owns one game
 * instance's full lifecycle — create from a definition, run its
 * {@link GameStateMachine}, stop it, and notifies listeners so the Bukkit
 * adapter can clean up worlds/entities per event.
 *
 * <p>Composes D1's pieces: each {@link Instance} pairs a
 * {@link GameStateMachine} with its {@link GameMap} and metadata.
 */
public final class InstanceManager {

    /** Definition of a game type: team layout and map wiring. */
    public record Definition(String typeId, int maxPlayersPerTeam, java.util.List<String> teamIds) {
    }

    /** One running game instance. */
    public static final class Instance {
        private final UUID id;
        private final String typeId;
        private final GameStateMachine state;
        private final GameMap map;

        Instance(UUID id, String typeId, GameStateMachine state, GameMap map) {
            this.id = id;
            this.typeId = typeId;
            this.state = state;
            this.map = map;
        }

        public UUID id() {
            return id;
        }

        public String typeId() {
            return typeId;
        }

        public GameStateMachine state() {
            return state;
        }

        public GameMap map() {
            return map;
        }
    }

    /** Raised on lifecycle misuse (unknown instance, wrong state). */
    public static final class InstanceException extends IllegalStateException {
        public InstanceException(String message) {
            super(message);
        }
    }

    private final Map<UUID, Instance> instances = new HashMap<>();
    private final Map<String, Definition> definitions = new HashMap<>();
    private final List<java.util.function.Consumer<Instance>> createListeners = new java.util.ArrayList<>();
    private final List<java.util.function.Consumer<Instance>> stopListeners = new java.util.ArrayList<>();

    /** Registers a game type definition. */
    public void register(Definition definition) {
        definitions.put(definition.typeId(), definition);
    }

    public Instance instance(UUID id) {
        return instances.get(id);
    }

    public java.util.Collection<Instance> all() {
        return java.util.Collections.unmodifiableCollection(instances.values());
    }

    public void onCreate(java.util.function.Consumer<Instance> listener) {
        createListeners.add(listener);
    }

    public void onStop(java.util.function.Consumer<Instance> listener) {
        stopListeners.add(listener);
    }

    /**
     * Creates a game from a definition: builds teams and an empty map and
     * leaves the machine in LOBBY. Returns the new instance.
     */
    public Instance create(String typeId) {
        Definition definition = definitions.get(typeId);
        if (definition == null) {
            throw new InstanceException("No such game type: " + typeId);
        }
        GameStateMachine state = new GameStateMachine(definition.maxPlayersPerTeam());
        GameMap map = new GameMap();
        for (String teamId : definition.teamIds()) {
            state.createTeam(teamId);
        }
        Instance instance = new Instance(UUID.randomUUID(), typeId, state, map);
        instances.put(instance.id(), instance);
        for (java.util.function.Consumer<Instance> listener : createListeners) {
            listener.accept(instance);
        }
        return instance;
    }

    /**
     * Restores a persisted instance: rebuilds it under its original id from
     * a definition and applies the machine snapshot (teams, scores, state).
     * Fires create listeners like a fresh create so adapters can re-attach.
     */
    public Instance restore(UUID id, String typeId, GameStateMachine.Snapshot snapshot) {
        Definition definition = definitions.get(typeId);
        if (definition == null) {
            throw new InstanceException("No such game type: " + typeId);
        }
        if (instances.containsKey(id)) {
            throw new InstanceException("Instance already exists: " + id);
        }
        GameStateMachine state = new GameStateMachine(definition.maxPlayersPerTeam());
        for (String teamId : definition.teamIds()) {
            state.createTeam(teamId);
        }
        state.restore(snapshot);
        Instance instance = new Instance(id, typeId, state, new GameMap());
        instances.put(instance.id(), instance);
        for (java.util.function.Consumer<Instance> listener : createListeners) {
            listener.accept(instance);
        }
        return instance;
    }

    /**
     * Stops a game: forces the machine to ENDING → CLOSED, fires the stop
     * listener, and removes the instance. Unknown ids throw so callers
     * surface bugs instead of silently ignoring them.
     */
    public void stop(UUID instanceId) {
        Instance instance = instances.get(instanceId);
        if (instance == null) {
            throw new InstanceException("No such instance: " + instanceId);
        }
        GameStateMachine state = instance.state();
        if (state.state() != GameStateMachine.State.CLOSED) {
            // Force-walk the machine to terminal state through legal steps.
            switch (state.state()) {
                case LOBBY -> state.transition(GameStateMachine.State.CLOSED);
                case STARTING -> {
                    state.transition(GameStateMachine.State.LOBBY);
                    state.transition(GameStateMachine.State.CLOSED);
                }
                case RUNNING -> {
                    state.transition(GameStateMachine.State.ENDING);
                    state.transition(GameStateMachine.State.CLOSED);
                }
                case ENDING -> state.transition(GameStateMachine.State.CLOSED);
                case CLOSED -> { /* already terminal */ }
            }
        }
        instances.remove(instanceId);
        for (java.util.function.Consumer<Instance> listener : stopListeners) {
            listener.accept(instance);
        }
    }
}
