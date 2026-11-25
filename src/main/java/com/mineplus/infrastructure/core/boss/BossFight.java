package com.mineplus.infrastructure.core.boss;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;

/**
 * Boss fight toolkit (mega-plan C6 / Phase C): multi-phase fights driven
 * by health thresholds. Pure computation — the host adapts its entities:
 * {@link #tick} receives the boss's current health from a supplier, walks
 * the phase ladder, and fires ability callbacks the feature registered.
 *
 * <p>Phases are declared in order; each triggers at or below a health
 * percentage. Abilities are named handlers keyed by phase, so features
 * keep their logic in small units and the toolkit handles sequencing,
 * one-shot-vs-repeat semantics, and inter-ability cooldowns.
 */
public final class BossFight {

    /** One phase: threshold (0-100 health %) and the abilities it enables. */
    public static final class Phase {
        final String name;
        final double threshold;
        final List<String> abilities;
        final boolean repeatAbilities;

        Phase(String name, double threshold, List<String> abilities, boolean repeatAbilities) {
            this.name = name;
            this.threshold = threshold;
            this.abilities = abilities;
            this.repeatAbilities = repeatAbilities;
        }

        public String name() {
            return name;
        }

        public double threshold() {
            return threshold;
        }

        public List<String> abilities() {
            return Collections.unmodifiableList(abilities);
        }
    }

    private final List<Phase> phases = new ArrayList<>();
    private final Map<String, BiConsumer<BossFight, String>> abilities = new LinkedHashMap<>();
    private final DoubleSupplier healthFraction;
    private final long cooldownTicks;
    private final long[] lastFire = {Long.MIN_VALUE / 2};
    private final long[] tickCounter = {0};
    private int currentPhase = -1;
    private Runnable onPhaseChange;

    public BossFight(DoubleSupplier healthFraction, long cooldownTicks) {
        this.healthFraction = healthFraction;
        this.cooldownTicks = Math.max(0, cooldownTicks);
    }

    /** Declares a phase; thresholds must be strictly decreasing per call. */
    public BossFight phase(String name, double thresholdPct, boolean repeatAbilities, String... abilities) {
        if (!phases.isEmpty() && thresholdPct >= phases.get(phases.size() - 1).threshold) {
            throw new IllegalArgumentException("Phase thresholds must decrease: " + name);
        }
        phases.add(new Phase(name, thresholdPct, List.of(abilities), repeatAbilities));
        return this;
    }

    /** Registers an ability handler: receives the fight and the phase name. */
    public BossFight ability(String name, BiConsumer<BossFight, String> handler) {
        abilities.put(name, handler);
        return this;
    }

    /** Runs once when a new phase starts (e.g. boss bar title update). */
    public BossFight onPhaseChange(Runnable listener) {
        onPhaseChange = listener;
        return this;
    }

    public int currentPhaseIndex() {
        return currentPhase;
    }

    public Phase currentPhase() {
        return currentPhase >= 0 && currentPhase < phases.size()
                ? phases.get(currentPhase) : null;
    }

    /**
     * Advances one tick: enters higher phases as health drops and fires
     * the phase's abilities under the shared cooldown. New phases fire
     * their abilities immediately (ignoring the cooldown) so the fight
     * never stalls on a phase transition.
     */
    public void tick() {
        tickCounter[0]++;
        double health = healthFraction.getAsDouble() * 100.0;
        for (int i = phases.size() - 1; i > currentPhase; i--) {
            if (health <= phases.get(i).threshold) {
                boolean changed = currentPhase != i;
                currentPhase = i;
                if (changed && onPhaseChange != null) {
                    onPhaseChange.run();
                }
                firePhaseAbilities(true);
                lastFire[0] = tickCounter[0];
                return;
            }
        }
        if (currentPhase >= 0 && tickCounter[0] - lastFire[0] > cooldownTicks) {
            firePhaseAbilities(false);
            lastFire[0] = tickCounter[0];
        }
    }

    private void firePhaseAbilities(boolean entering) {
        Phase phase = phases.get(currentPhase);
        if (entering || phase.repeatAbilities) {
            for (String ability : phase.abilities) {
                BiConsumer<BossFight, String> handler = abilities.get(ability);
                if (handler != null) {
                    handler.accept(this, phase.name);
                }
            }
        }
    }
}
