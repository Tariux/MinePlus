package com.mineplus.gun.weapon;

import com.mineplus.gun.MineplusGunPlugin;
import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.registry.ItemMagazine;
import com.mineplus.infrastructure.registry.ItemRegistry;
import com.mineplus.infrastructure.registry.ItemState;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

/**
 * Builds the {@link ItemStack} for a weapon: Core pack-item identity via the
 * pack API, plus the module's runtime state through the Core primitives —
 * {@link ItemState} for the weapon-id stamp and {@link ItemMagazine} for the
 * live round count and per-stack identity.
 *
 * <p>The module keeps no ammo or identity tags of its own: every read and write
 * of a round count goes through the Core magazine, so the in-memory
 * authoritative value while a gun is in the hand and the PDC value that survives
 * a log-out are one and the same mechanism, not two that can drift apart.</p>
 *
 * <p>Lore is the weapon's stat block plus its controls; the Core content layer
 * supplies the display name. With {@code PACK.ENABLED: false} the Core returns
 * the vanilla carrier; identity and gameplay still work.</p>
 */
public final class WeaponItemFactory {

    public static final String NAMESPACE = "mineplusgun";

    /** The Core magazine slot name this module's rounds persist under. */
    public static final String AMMO_SLOT = "ammo";

    private final MineplusGunPlugin plugin;
    private final ItemRegistry itemRegistry;
    private final com.mineplus.pack.PackApi packApi;

    private final ItemState weaponState;
    private final ItemMagazine magazine;

    public WeaponItemFactory(MineplusGunPlugin plugin, PluginContext context) {
        this.plugin = plugin;
        this.itemRegistry = context.itemRegistry();
        this.packApi = context.packApi();
        this.weaponState = new ItemState(plugin, "weapon");
        this.magazine = context.magazine(plugin, AMMO_SLOT);
    }

    /** A fresh weapon stack with a full magazine and its own identity tag. */
    public ItemStack create(WeaponDefinition definition) {
        ItemStack stack = packApi.createItem(NAMESPACE, definition.id());
        if (stack == null || stack.getType().isAir()) {
            // The pack subsystem is disabled or the item is not registered:
            // degrade to the vanilla carrier. Behaviour is identical; only the
            // custom model is missing.
            if (packApi.isAvailable()) {
                plugin.getLogger().warning("[MineplusGun] Pack item '" + NAMESPACE + ":" + definition.id()
                        + "' is not registered; issuing the vanilla carrier instead.");
            }
            stack = new ItemStack(definition.backingMaterial());
        }

        weaponState.setString(stack, definition.id());
        magazine.issue(stack, capacity(definition));

        // The stat block lore is weapon gameplay, not content presentation.
        var meta = stack.getItemMeta();
        if (meta != null) {
            meta.setLore(defaultLore(definition));
            stack.setItemMeta(meta);
        }
        applyInitialFrame(stack, definition);
        return stack;
    }

    /** The Core magazine service this module's stacks are read through. */
    public ItemMagazine magazine() {
        return magazine;
    }

    /** The round capacity of a weapon. */
    public static int capacity(WeaponDefinition definition) {
        return definition.usesAmmo() ? Math.max(0, definition.magazine()) : 0;
    }

    /**
     * Framed weapons leave the factory on their loaded state (a fresh magazine
     * is full); the idle base otherwise, which both the pack dispatch and the
     * legacy host overrides read as the resting model.
     */
    private void applyInitialFrame(ItemStack stack, WeaponDefinition definition) {
        if (!definition.hasFrames()) {
            return;
        }
        var frames = definition.frames();
        int state = frames.loadedState() > 0 && definition.magazine() > 0
                ? frames.loadedState()
                : frames.idleState();
        com.mineplus.pack.item.ItemFrameWriter.apply(stack, definition.frameStackValue(state));
    }

    private List<String> defaultLore(WeaponDefinition definition) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + definition.type().categoryLabel());
        lore.add(ChatColor.GRAY + "Damage: " + ChatColor.WHITE + format(definition.damage()));
        if (definition.usesAmmo()) {
            lore.add(ChatColor.GRAY + "Magazine: " + ChatColor.WHITE + definition.magazine()
                    + ChatColor.DARK_GRAY + "  ·  " + ChatColor.GRAY + definition.fireMode().name());
        }
        lore.add(ChatColor.DARK_GRAY + "Right-click: fire");
        lore.add(ChatColor.DARK_GRAY + "F (swap hands): reload");
        if (definition.scope()) {
            lore.add(ChatColor.DARK_GRAY + "Left-click: scope");
        }
        return lore;
    }

    private static String format(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** The weapon id stamped on the stack, or {@code null}. */
    public String weaponId(ItemStack stack) {
        String id = weaponState.readString(stack);
        if (id != null && !id.isBlank()) {
            return id;
        }
        // Fallback: Core item identity (works even if this module's runtime PDC
        // was stripped, e.g. an item from a creative picker).
        String coreKey = itemRegistry.readItemKey(stack);
        if (coreKey == null) {
            return null;
        }
        int colon = coreKey.indexOf(':');
        return colon >= 0 ? coreKey.substring(colon + 1) : coreKey;
    }
}
