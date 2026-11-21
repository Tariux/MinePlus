package com.mineplus.fun.juicer;

import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import com.mineplus.infrastructure.registry.ItemRegistry;
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
        double maxHealth = MobAttributes.get(player, "MAX_HEALTH", 20.0);
        player.setHealth(Math.min(maxHealth, player.getHealth() + healthPoints));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedTicks, 0, true, true, true));
    }
}
