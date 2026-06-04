package com.mineplus.game.juicer;

import com.mineplus.infrastructure.registry.ItemRegistry;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class JuiceConsumeListener implements Listener {

    private final ItemRegistry itemRegistry;

    public JuiceConsumeListener(ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack consumed = event.getItem();
        String key = itemRegistry.readItemKey(consumed);
        if (key == null) {
            return;
        }

        if (key.equalsIgnoreCase(JuicerKeys.CARROT_JUICE_ITEM)) {
            applyJuiceEffects(event.getPlayer(), 3.0, 5 * 20);
            return;
        }

        if (key.equalsIgnoreCase(JuicerKeys.MELON_JUICE_ITEM)) {
            applyJuiceEffects(event.getPlayer(), 2.0, 10 * 20);
        }
    }

    private void applyJuiceEffects(Player player, double healthPoints, int speedTicks) {
        double maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH) == null
                ? 20.0
                : player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
        player.setHealth(Math.min(maxHealth, player.getHealth() + healthPoints));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedTicks, 0, true, true, true));
    }
}
