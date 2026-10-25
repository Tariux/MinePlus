package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponItemFactory;
import com.mineplus.gun.weapon.WeaponRegistry;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.module.ModuleFeature;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps issued weapon stacks current across config/arsenal upgrades. A stack
 * is refreshed when its stamped weapon id is retired (alias → replacement) or
 * when its carrier material drifted from the definition's (e.g. guns issued
 * before the crossbow-carrier switch). The swap conserves the stack's uid and
 * live rounds. Unmapped retired items (the Bowie knife) are removed. Live,
 * current stacks are never touched.
 */
public final class MigrationFeature extends ModuleFeature implements Listener {

    /** Retired ids with no current replacement (removed instead of converted). */
    private static final List<String> REMOVED_LEGACY = List.of("knife_bowie");

    private final GunServices services;
    private boolean registered;

    public MigrationFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "migration";
    }

    @Override
    protected void onEnable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        registered = true;
        int converted = 0;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            converted += migrate(player);
        }
        if (converted > 0) {
            plugin.getLogger().info("[MineplusGun] Refreshed " + converted + " weapon stack(s) to the current arsenal.");
        }
    }

    @Override
    protected void onDisable() {
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        migrate(event.getPlayer());
    }

    /** Swaps every outdated weapon stack in the player's inventory; returns the converted count. */
    private int migrate(Player player) {
        WeaponRegistry registry = services.registry();
        WeaponItemFactory factory = services.itemFactory();
        if (registry == null || factory == null) {
            return 0;
        }
        int converted = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            String stamped = stampedId(stack);
            if (stamped == null) {
                continue;
            }
            String replacementId = stamped;
            boolean retired = !registry.isCurrent(stamped);
            if (retired) {
                String alias = WeaponRegistry.aliasOf(stamped);
                if (alias == null) {
                    if (REMOVED_LEGACY.contains(stamped)) {
                        player.getInventory().setItem(slot, null);
                        player.sendMessage(ChatColor.GRAY + "The " + ChatColor.WHITE
                                + label(stamped) + ChatColor.GRAY + " was retired.");
                        converted++;
                    }
                    continue;
                }
                replacementId = alias;
            }
            WeaponDefinition replacement = registry.get(replacementId);
            if (replacement == null) {
                continue;
            }
            // A current weapon whose carrier drifted (carrier switch upgrades)
            // is refreshed too, so old guns gain the crossbow draw mechanics.
            if (!retired && stack.getType() == replacement.backingMaterial()) {
                continue;
            }
            ItemStack fresh = factory.create(replacement);
            // Conserve identity and live rounds across the swap.
            String uid = factory.readUid(stack);
            if (uid != null && !uid.isBlank()) {
                factory.writeUid(fresh, uid);
            }
            int rounds = factory.readAmmo(stack);
            if (rounds >= 0 && replacement.usesAmmo()) {
                factory.writeAmmo(fresh, Math.min(rounds, replacement.magazine()));
            }
            player.getInventory().setItem(slot, fresh);
            if (retired) {
                player.sendMessage(ChatColor.GRAY + "Your " + ChatColor.WHITE + label(stamped)
                        + ChatColor.GRAY + " was exchanged for " + replacement.displayName() + ChatColor.GRAY + ".");
            }
            converted++;
        }
        return converted;
    }

    private static String label(String id) {
        return id.replace('_', ' ');
    }

    /** The module weapon stamp on the stack (runtime tag, then Core identity), or {@code null}. */
    private String stampedId(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        String id = services.itemFactory().weaponId(stack);
        return id == null || id.isBlank() ? null : id.toLowerCase(java.util.Locale.ROOT);
    }
}
