package com.mineplus.infrastructure.command.sub;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.command.SubCommand;
import com.mineplus.infrastructure.mob.custom.CustomMobApi;
import com.mineplus.infrastructure.mob.custom.CustomMobDefinition;
import com.mineplus.infrastructure.mob.custom.MobRenderSpec;
import com.mineplus.infrastructure.mob.custom.MobYamlLoader;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.render.ModelBundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

/**
 * {@code /mineplus mob} — operator tooling for custom mobs: list, spawn, the
 * YAML definition reload, per-mob pack validation, a model inspector, the
 * one-shot clip player and the bone-marker debug toggle.
 */
public final class MobSubCommand implements SubCommand {

    private final PluginContext context;
    private final MobYamlLoader yamlMobs;

    public MobSubCommand(PluginContext context, MobYamlLoader yamlMobs) {
        this.context = context;
        this.yamlMobs = yamlMobs;
    }

    @Override
    public String name() {
        return "mob";
    }

    @Override
    public String description() {
        return "List, spawn and debug Mineplus custom mobs.";
    }

    @Override
    public String usage() {
        return "/mineplus mob <list|spawn|reload|validate|inspect|anim|bones|pack> ...";
    }

    @Override
    public String permission() {
        return "mineplus.admin.mob";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                listDefinitions(sender);
                return true;
            }
            case "spawn" -> {
                return spawn(sender, args);
            }
            case "reload" -> {
                if (yamlMobs == null) {
                    sender.sendMessage(ChatColor.RED + "No mobs/ folder is loaded (custom mobs disabled?).");
                    return true;
                }
                int count = yamlMobs.reload();
                sender.sendMessage(ChatColor.GREEN + "" + count + " YAML definition(s) (re)loaded.");
                return true;
            }
            case "validate" -> {
                validate(sender, args.length > 1 ? args[1] : null);
                return true;
            }
            case "inspect" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /mineplus mob inspect <mob>");
                    return true;
                }
                inspect(sender, args[1]);
                return true;
            }
            case "anim" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /mineplus mob anim <mob> <clip|attack>");
                    return true;
                }
                playAnim(sender, args[1], args[2]);
                return true;
            }
            case "bones" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Bone markers are "
                            + (context.customMobManager().boneDebug() ? "ON" : "OFF")
                            + ChatColor.GRAY + " (/mineplus mob bones on|off)");
                    return true;
                }
                boolean on = args[1].equalsIgnoreCase("on");
                context.customMobManager().setBoneDebug(on);
                sender.sendMessage(ChatColor.GREEN + "Bone markers " + (on ? "ON" : "OFF")
                        + ChatColor.GRAY + " — a tiny cube marks every bone pivot of every rigged mob.");
                return true;
            }
            case "pack" -> {
                packSummary(sender);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void listDefinitions(CommandSender sender) {
        CustomMobApi mobs = context.customMobs();
        List<CustomMobDefinition> definitions = mobs.definitions();
        sender.sendMessage(ChatColor.GOLD + "Custom mobs: " + definitions.size());
        if (definitions.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "- none registered");
            return;
        }
        for (CustomMobDefinition definition : definitions) {
            String render = definition.renderSpec() == null
                    ? "legacy model" : definition.renderSpec().type().name();
            sender.sendMessage(ChatColor.YELLOW + "- " + definition.id()
                    + ChatColor.GRAY + " [" + definition.baseType() + ", " + render + "]"
                    + " live " + mobs.liveCount(definition.id())
                    + ChatColor.AQUA + " " + String.valueOf(mobs.rigStatus(definition.id())));
        }
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can spawn custom mobs.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /mineplus mob spawn <mob>");
            return true;
        }
        LivingEntity mob = context.customMobs().spawn(player.getLocation(), args[1]);
        if (mob == null) {
            sender.sendMessage(ChatColor.RED + "Could not spawn '" + args[1]
                    + "' (unknown id, subsystem disabled, or the server refused the base entity).");
            return true;
        }
        sender.sendMessage(ChatColor.GREEN + "Spawned '" + args[1] + "' (" + mob.getUniqueId() + ")");
        return true;
    }

    private void validate(CommandSender sender, String id) {
        CustomMobApi mobs = context.customMobs();
        List<CustomMobDefinition> definitions;
        if (id == null) {
            definitions = mobs.definitions();
        } else {
            CustomMobDefinition single = mobs.definition(id);
            if (single == null) {
                sender.sendMessage(ChatColor.RED + "Unknown custom mob '" + id + "'.");
                return;
            }
            definitions = List.of(single);
        }
        int broken = 0;
        for (CustomMobDefinition definition : definitions) {
            String failure = mobs.rigFailure(definition.id());
            if (failure == null) {
                sender.sendMessage(ChatColor.GREEN + definition.id() + ChatColor.GRAY + ": "
                        + String.valueOf(mobs.rigStatus(definition.id())));
            } else {
                broken++;
                sender.sendMessage(ChatColor.RED + definition.id() + ChatColor.GRAY + ": " + failure);
            }
        }
        sender.sendMessage(ChatColor.GOLD + "Broken definitions: " + broken
                + ChatColor.GRAY + " (a broken model never reaches the displays; its fallback applies).");
    }

    private void inspect(CommandSender sender, String id) {
        CustomMobDefinition definition = context.customMobs().definition(id);
        if (definition == null) {
            sender.sendMessage(ChatColor.RED + "Unknown custom mob '" + id + "'.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Mob " + ChatColor.WHITE + definition.id());
        sender.sendMessage(ChatColor.YELLOW + "Base: " + ChatColor.WHITE + definition.baseType()
                + ChatColor.GRAY + " persistent=" + definition.persistent());
        MobRenderSpec spec = definition.renderSpec();
        if (spec == null) {
            sender.sendMessage(ChatColor.YELLOW + "Render: " + ChatColor.GRAY + "legacy model path");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Render: " + ChatColor.WHITE + spec.type()
                    + ChatColor.GRAY + " source '" + spec.sourceName() + "'"
                    + " scale=" + spec.scale() + " fallback=" + spec.fallback());
            sender.sendMessage(ChatColor.YELLOW + "Clip aliases: " + ChatColor.WHITE
                    + (spec.animationMap().isEmpty() ? "-" : spec.animationMap().toString()));
            sender.sendMessage(ChatColor.YELLOW + "Hand items: " + ChatColor.WHITE
                    + (spec.handItems().isEmpty() ? "-" : spec.handItems().keySet().toString()));
        }

        ModelBundle bundle = context.customMobManager().bundle(definition.id());
        if (bundle == null) {
            sender.sendMessage(ChatColor.YELLOW + "Bundle: " + ChatColor.GRAY + "none"
                    + " (retexture and NONE types carry no display bundle)");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Bundle: " + ChatColor.WHITE + bundle.statusLine());
            ModelData model = bundle.model();
            if (model != null) {
                sender.sendMessage(ChatColor.YELLOW + "Model '" + ChatColor.WHITE + model.sourceName()
                        + ChatColor.YELLOW + "': " + ChatColor.WHITE + model.boneCount() + " bone(s)"
                        + ChatColor.GRAY + ", texture " + model.textureWidth() + "x" + model.textureHeight()
                        + ", " + bundle.pieceCount() + " piece(s), ~" + bundle.displayEstimate(0) + " display(s)");
                sender.sendMessage(ChatColor.YELLOW + "Bones: " + ChatColor.WHITE
                        + boneList(model));
                if (!model.animations().isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Clips: " + ChatColor.WHITE
                            + String.join(", ", model.animations().keySet()));
                }
                if (!model.issues().isEmpty()) {
                    for (String issue : model.issues()) {
                        sender.sendMessage(ChatColor.RED + "  issue: " + issue);
                    }
                }
            }
        }

        List<LivingEntity> live = context.customMobs().liveMobs(definition.id());
        sender.sendMessage(ChatColor.YELLOW + "Live mobs: " + ChatColor.WHITE + live.size());
        for (LivingEntity mob : live) {
            String pose = context.customMobs().poseSnapshot(mob);
            String isolation = context.customMobs().isolationLabel(mob);
            sender.sendMessage(ChatColor.GRAY + "- " + mob.getUniqueId()
                    + (pose == null ? ChatColor.GRAY + " no rig" : ChatColor.AQUA + " " + pose)
                    + (isolation == null ? "" : ChatColor.GRAY + " ai=" + isolation));
        }
    }

    private String boneList(ModelData model) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < model.boneCount(); i++) {
            names.add(model.bone(i).name());
        }
        return String.join(", ", names);
    }

    private void playAnim(CommandSender sender, String id, String clipName) {
        CustomMobApi mobs = context.customMobs();
        List<LivingEntity> live = mobs.liveMobs(id);
        if (live.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No live mobs of '" + id + "' to animate.");
            return;
        }
        int played = 0;
        for (LivingEntity mob : live) {
            boolean ok = clipName.equalsIgnoreCase("attack")
                    ? mobs.playAttack(mob)
                    : mobs.playAnimation(mob, clipName);
            if (ok) {
                played++;
            }
        }
        sender.sendMessage(played > 0
                ? ChatColor.GREEN + "Playing '" + clipName + "' on " + played + "/" + live.size() + " mob(s)."
                : ChatColor.RED + "No mob of '" + id + "' has a clip named '" + clipName
                        + "' (attack needs an attack clip; all need a rig).");
    }

    private void packSummary(CommandSender sender) {
        CustomMobApi mobs = context.customMobs();
        int rigged = 0;
        int fallback = 0;
        for (CustomMobDefinition definition : mobs.definitions()) {
            String status = mobs.rigStatus(definition.id());
            if (status != null && (status.contains("fallback") || status.contains("base entity"))) {
                fallback++;
            } else {
                rigged++;
            }
        }
        sender.sendMessage(ChatColor.GOLD + "Custom-mob presentation: " + rigged + " rigged, "
                + fallback + " on fallback");
        sender.sendMessage(ChatColor.GRAY + "Per-definition detail: /mineplus mob validate");
        sender.sendMessage(ChatColor.GRAY + "Resource pack itself: /mineplus pack");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0],
                    List.of("list", "spawn", "reload", "validate", "inspect", "anim", "bones", "pack"),
                    completions);
            Collections.sort(completions);
            return completions;
        }
        if (args.length == 2 && List.of("spawn", "validate", "inspect", "anim")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> ids = new ArrayList<>();
            for (CustomMobDefinition definition : context.customMobs().definitions()) {
                ids.add(definition.id());
            }
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], ids, completions);
            Collections.sort(completions);
            return completions;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("anim")) {
            List<String> clips = new ArrayList<>();
            ModelBundle bundle = args[1] == null
                    ? null : context.customMobManager().bundle(args[1]);
            if (bundle != null && bundle.model() != null) {
                clips.addAll(bundle.model().animations().keySet());
            }
            clips.add("attack");
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[2], clips, completions);
            Collections.sort(completions);
            return completions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bones")) {
            List<String> completions = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], List.of("on", "off"), completions);
            Collections.sort(completions);
            return completions;
        }
        return Collections.emptyList();
    }
}
