package com.mineplus.infrastructure.core.multiblock.formation;

import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Module-facing formation surface. Register {@link FormationPattern}s, listen
 * for form/break, and run manual checks — the automatic block-listener wiring
 * calls {@link #checkFormed}/{@link #checkBroken} for you.
 */
public final class FormationApi {

    private final FormationManager manager;

    public FormationApi(FormationManager manager) {
        this.manager = manager;
    }

    /** Registers a pattern; a duplicate id replaces the old one. */
    public void register(FormationPattern pattern) {
        manager.registry().register(pattern);
    }

    public FormationPattern get(String id) {
        return manager.registry().get(id);
    }

    public void unregister(String id) {
        manager.registry().unregister(id);
    }

    /** All registered patterns, keyed by id (read-only). */
    public Map<String, FormationPattern> patterns() {
        return manager.registry().patternsView();
    }

    /** Registers a listener for {@link FormationEvent}s (formed and broken). */
    public void onFormation(java.util.function.Consumer<FormationEvent> listener) {
        manager.hookBus().registerFormationListener(listener);
    }

    /** Manually checks a location for a new formation (normally automatic). */
    public String checkFormed(Location anchor, Player actor) {
        return manager.checkFormed(anchor, actor);
    }

    /** Manually reports a block change inside a possibly-live formation (normally automatic). */
    public String checkBroken(Location changed, Player actor) {
        return manager.checkBroken(changed, actor);
    }

    /** Live formation boxes, for diagnostics: box key -> pattern id. */
    public Map<String, String> liveFormations() {
        return manager.liveFormationsView();
    }
}
