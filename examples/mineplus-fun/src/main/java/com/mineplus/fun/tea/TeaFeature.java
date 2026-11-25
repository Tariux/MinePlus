package com.mineplus.fun.tea;

import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.infrastructure.module.ModuleFeature;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.item.PackItemDefinition;
import java.util.List;
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
 * <p>The tea cup is registered as a pack item ({@code fun:tea_cup}) bound to
 * the {@code tea} model, so the generated resource pack renders the full 3D
 * model on the held item instead of the vanilla potion texture. The feature
 * exposes {@code /tea <give|spawn|play|list|debug|stop|remove>} — the debug
 * action plays every clip in the model one after another for a quick
 * animation check.
 */
public final class TeaFeature extends ModuleFeature {

    private PackApi packApi;

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

        packApi = context.packApi();
        // Registered before the coordinated reload, so the model/texture assets
        // attach during the reload-driven pack recompile — the same contract as
        // the pack showcase feature.
        packApi.registerItem(PackItemDefinition.builder(
                        TeaKeys.NAMESPACE, TeaKeys.TEA_ITEM,
                        org.bukkit.Material.POTION,
                        TeaKeys.MODEL_KEY)
                .displayName("Tea Cup")
                .category(ItemCategory.UTILITY)
                .descriptionLines(List.of(
                        "Freshly brewed, still steaming.",
                        "Spawn the cup with /tea spawn, then /tea play <animation>"))
                .build());
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new TeaSubCommand(context, packApi);
    }
}
