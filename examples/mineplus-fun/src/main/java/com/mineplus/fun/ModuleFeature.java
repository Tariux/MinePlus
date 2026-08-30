package com.mineplus.fun;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Lifecycle contract for one game feature of this module.
 *
 * <p>Features are declared once in the plugin main's feature list; the bootstrap
 * then runs, in order: {@link #start()} for every feature, one <em>coordinated</em>
 * {@code reloadAll()} for the whole module, and {@link #registerCommand()} for
 * every feature. Teardown runs {@link #stop()} in reverse enable order.
 *
 * <p>Contract rules:
 * <ul>
 *   <li>{@link #onEnable()} installs resources and registers hooks, listeners and
 *       GUIs — it must <b>not</b> call {@code reloadAll()}. The module performs
 *       exactly one reload after all features started, so N features cost one
 *       model/registry reload instead of N.</li>
 *   <li>Bootstrap and teardown are exception-isolated per feature (the same
 *       philosophy the Core applies to hook dispatch): a feature that throws is
 *       logged as severe and skipped; it never aborts the remaining features.</li>
 *   <li>{@link #onDisable()} must tolerate a feature that failed mid-enable:
 *       null-guard every collaborator it cleans up.</li>
 * </ul>
 */
public abstract class ModuleFeature {

    protected final JavaPlugin plugin;
    protected final PluginContext context;

    protected ModuleFeature(JavaPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.context = context;
    }

    /** Feature identity: log prefix and top-level command label. */
    public abstract String id();

    /**
     * Installs resources and registers hooks, listeners, and GUIs.
     * Must not call {@code jsonInfrastructureApi().reloadAll()}.
     */
    protected abstract void onEnable();

    /** Optional cleanup: cancel tasks, release entities. Called in reverse enable order. */
    protected void onDisable() {
    }

    /** Top-level command for this feature (registered under {@link #id()}), or {@code null}. */
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
