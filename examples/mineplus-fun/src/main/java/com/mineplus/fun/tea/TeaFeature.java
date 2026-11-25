package com.mineplus.fun.tea;

import com.mineplus.infrastructure.module.ModuleFeature;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Wires the tea cup showcase into the Mineplus Core engine.
 *
 * <p>The {@code tea.bbmodel} (a cup, saucer, handle and steam rig animated with
 * four clips: {@code drink_firstperson}, {@code fill}, {@code empty} and the
 * looping {@code steam_idle}) and its {@code tea.png} texture ship inside this
 * module's jar and are installed into the Core's data folder through the Core's
 * module toolkit. The PNG sits next to the bbmodel under the model's texture
 * name so the Core's load-time baker picks it up.
 *
 * <p>The feature registers the {@code tea_cup} item ({@code /tea give}) and
 * exposes {@code /tea <spawn|play|list|debug|stop|remove>} — the debug action
 * plays every clip in the model one after another for a quick animation check.
 */
public final class TeaFeature extends ModuleFeature {

    public TeaFeature(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "tea";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        // Model + texture ship as one unit; overwrite = true so a module update
        // can fix rendering. The texture must sit next to the bbmodel under the
        // name the model references it by (tea.png).
        support.installDefault(plugin, "defaults/models/tea.bbmodel", "models/tea.bbmodel", true);
        support.installDefault(plugin, "defaults/models/tea.png", "models/tea.png", true);

        context.itemRegistry().register(new TeaItemDefinition());
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new TeaSubCommand(context);
    }
}
