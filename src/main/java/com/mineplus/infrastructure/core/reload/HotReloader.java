package com.mineplus.infrastructure.core.reload;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Hot reload coordinator (mega-plan C7 / Phase C): re-applies
 * config-driven settings to live subsystems without a server restart.
 *
 * <p>Subsystems register a reload step: a settings supplier (usually
 * {@code configManager::getConfig} plus a getter for its slice) and an
 * applier that pushes the fresh settings into the subsystem. Reloading
 * walks the steps in registration order and isolates failures — one
 * broken subsystem cannot stop the others; a failed step is logged, its
 * previous settings keep running, and the summary report names it.
 */
public final class HotReloader {

    /** Result of one reload: applied and failed step names. */
    public record ReloadReport(List<String> applied, List<String> failed) {

        public boolean success() {
            return failed.isEmpty();
        }
    }

    /** One subsystem's reload step: fresh settings in, apply out. */
    private static final class Step<T> {
        final String name;
        final Supplier<T> settings;
        final BiConsumer<T, T> applier;
        T lastApplied;

        Step(String name, Supplier<T> settings, BiConsumer<T, T> applier) {
            this.name = name;
            this.settings = settings;
            this.applier = applier;
        }
    }

    private final Logger logger;
    private final List<Step<Object>> steps = new ArrayList<>();

    public HotReloader(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers a subsystem. {@code settings} produces the fresh settings
     * object each reload; {@code applier} receives (previous, fresh) —
     * the previous value is null on first apply.
     */
    @SuppressWarnings("unchecked")
    public <T> void register(String name, Supplier<T> settings, BiConsumer<T, T> applier) {
        steps.add((Step<Object>) new Step<>(name, settings, applier));
    }

    /**
     * Reloads every registered subsystem. Returns the report; the caller
     * (command/log) decides how to surface it. Never throws.
     */
    public ReloadReport reloadAll() {
        List<String> applied = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Step<Object> step : steps) {
            try {
                Object fresh = step.settings.get();
                step.applier.accept(step.lastApplied, fresh);
                step.lastApplied = fresh;
                applied.add(step.name);
            } catch (RuntimeException e) {
                logger.warning("HotReloader: " + step.name
                        + " reload failed, keeping previous settings: " + e.getMessage());
                failed.add(step.name);
            }
        }
        return new ReloadReport(applied, failed);
    }
}
