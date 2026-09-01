package com.mineplus.fun.cabinet;

import com.mineplus.fun.ModuleFeature;
import com.mineplus.fun.cabinet.gui.CabinetGui;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Acacia cabinet: a two-level multiblock whose model doubles as its state —
 * level 1 renders the closed cabinet, level 2 the open one. Right-click
 * (hook-driven, no JSON {@code gui} key) opens an 18-slot storage menu and
 * swaps to the open model; closing the menu swaps back. Contents persist in
 * {@code stateData} and are dropped when the cabinet is broken or removed.
 */
public final class CabinetFeature extends ModuleFeature {

    public CabinetFeature(JavaPlugin plugin, com.mineplus.infrastructure.PluginContext context) {
        super(plugin, context);
    }

    @Override
    public String id() {
        return "cabinet";
    }

    @Override
    protected void onEnable() {
        var support = context.moduleSupport();
        support.installDefault(plugin, "defaults/models/acacia_cabinet_top_closed.bbmodel", "models/acacia_cabinet_top_closed.bbmodel", true);
        support.installDefault(plugin, "defaults/models/acacia_cabinet_open.bbmodel", "models/acacia_cabinet_open.bbmodel", true);
        support.installDefault(plugin, "defaults/models/acacia_cabinet_front.png", "models/acacia_cabinet_front.png", true);
        support.installDefault(plugin, "defaults/models/acacia_cabinet_top.png", "models/acacia_cabinet_top.png", true);
        // Raised plate budgets: the vinery wood textures are gradient-heavy
        // (60+ distinct colors per 16x16 sprite), which greedy-merges past the
        // default 96-plate per-face ceiling — over-budget faces would lose their
        // texel bake and fall back to the resolver's white concrete.
        support.installDefault(plugin, "defaults/models/acacia_cabinet_top_closed.meta.json", "models/acacia_cabinet_top_closed.meta.json", true);
        support.installDefault(plugin, "defaults/models/acacia_cabinet_open.meta.json", "models/acacia_cabinet_open.meta.json", true);
        support.installDefault(plugin, "defaults/multiblocks/cabinet.json", "multiblocks/cabinet.json", false);

        context.infrastructureApi().registerGui(
                CabinetKeys.GUI_KEY,
                new CabinetGui(
                        plugin,
                        context.infrastructureEngine().registry(),
                        context.infrastructureEngine().lifecycleManager(),
                        context.infrastructureApi()
                )
        );

        context.infrastructureApi().registerHook(
                CabinetKeys.MACHINE_ID,
                new CabinetHook(context, context.infrastructureEngine().lifecycleManager())
        );
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new CabinetSubCommand(context);
    }
}
