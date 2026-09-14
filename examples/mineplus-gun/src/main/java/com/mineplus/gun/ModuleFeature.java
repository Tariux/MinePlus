package com.mineplus.gun;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lifecycle contract for one feature of the MineplusGun module — the same
 * shape as {@code mineplus-fun}'s {@code ModuleFeature}.
 *
 * <p>Features are declared once in the plugin main's feature list; the
 * bootstrap runs {@link #start()} for every feature, exactly one coordinated
 * {@code reloadAll()} for the whole module, then {@link #registerCommand()}
 * for every feature. Teardown runs {@link #stop()} in reverse enable order.
 */
public abstract class ModuleFeature {

    protected final JavaPlugin plugin;
    protected final PluginContext context;

    protected ModuleFeature(JavaPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    /** Feature identity: log prefix. */
    public abstract String id();

    /**
     * Installs resources and registers hooks, listeners, and GUIs.
     * Must not call {@code jsonInfrastructureApi().reloadAll()}.
     */
    protected abstract void onEnable();

    /** Optional cleanup: cancel tasks, release entities. Called in reverse enable order. */
    protected void onDisable() {
    }

    /** Top-level command for this feature, or {@code null}. */
    protected SubCommand command() {
        return null;
    }

    public final void start() {
        try {
            onEnable();
        } catch (Exception exception) {
            plugin.getLogger().severe("Feature '" + id() + "' failed to enable; isolating and continuing: "
                    + exception);
        }
    }

    public final void registerCommand() {
        SubCommand command = command();
        if (command == null) {
            return;
        }
        try {
            context.moduleSupport().registerCommand(plugin, id(), command);
        } catch (Exception exception) {
            plugin.getLogger().severe("Feature '" + id() + "' command registration failed; isolating and continuing: "
                    + exception);
        }
    }

    public final void stop() {
        try {
            onDisable();
        } catch (Exception exception) {
            plugin.getLogger().severe("Feature '" + id() + "' failed to disable cleanly: " + exception);
        }
    }
}
