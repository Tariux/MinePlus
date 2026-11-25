package com.mineplus.fun.tea;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.infrastructure.module.ModuleFeature;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.item.PackItemDefinition;
import java.util.List;
import org.bukkit.Material;
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
 * <p>The tea cup is a framed pack item ({@code fun:tea_cup}): at enable the
 * model's clips are baked into resource-pack frame models ({@link TeaBaker})
 * and the animations play through the pack itself ({@link TeaAnimations}) —
 * the same resource-pack look in hand, in a display and in an inventory, with
 * no virtual-engine texel bake. {@code steam_idle} loops by default; a right
 * click while holding the cup plays the drink clip. The feature exposes
 * {@code /tea <give|spawn|play|list|debug|stop|remove>}.
 */
public final class TeaFeature extends ModuleFeature {

    private PackApi packApi;
    private TeaAnimations animations;

    public TeaFeature(JavaPlugin plugin, PluginContext context) {
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
        // Registered before the coordinated reload, so the baked frame models
        // and the texture attach during the reload-driven pack recompile — the
        // same contract as the pack showcase feature.
        TeaAnimations.ItemFramesHandle frames = new TeaAnimations.ItemFramesHandle(
                TeaBaker.bake(context, plugin.getLogger()));
        PackItemDefinition.Builder builder = PackItemDefinition.builder(
                        TeaKeys.NAMESPACE, TeaKeys.TEA_ITEM,
                        Material.POTION,
                        frames.available() ? null : TeaKeys.MODEL_KEY)
                .displayName("Tea Cup")
                .category(ItemCategory.UTILITY)
                .descriptionLines(List.of(
                        "Freshly brewed, still steaming.",
                        "Spawn the cup with /tea spawn, then /tea play <animation>"));
        if (frames.available()) {
            builder.frames(frames.spec());
        }
        PackItemDefinition definition = builder.build();
        packApi.registerItem(definition);

        animations = new TeaAnimations(plugin, context, definition, frames);
        animations.start();
    }

    @Override
    protected void onDisable() {
        if (animations != null) {
            animations.stop();
            animations = null;
        }
    }

    @Override
    protected com.mineplus.infrastructure.command.SubCommand command() {
        return new TeaSubCommand(context, packApi, animations);
    }
}
