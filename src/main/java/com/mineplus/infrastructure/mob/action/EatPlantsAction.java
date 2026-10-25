package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;

/**
 * The mob eats the plant it stands in: the plant block is removed and the
 * eater gets a small compensation — instant growth for babies, occasional
 * regeneration for damaged adults. Modeled on the reference mod's "eat"
 * action ({@code mobEatGrassOrFlower}) without the grass-block trampling.
 * Expected fire interval: every couple of minutes per grazing mob.
 */
public final class EatPlantsAction implements BehaviorAction {

    @Override
    public String id() {
        return "eat_plants";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        Block foot = context.footBlock();
        Material type = foot.getType();
        boolean edible = type == Material.SHORT_GRASS || type == Material.TALL_GRASS
                || type == Material.FERN || type == Material.LARGE_FERN
                || Tag.FLOWERS.isTagged(type) || type == Material.SNOW;
        if (!edible) {
            return false;
        }
        if (!context.tryBreak(foot, false)) {
            return false;
        }
        context.world().playSound(foot.getLocation().add(0.5, 0.5, 0.5), Sound.ENTITY_GENERIC_EAT, 0.8f, 1.0f);
        LivingEntity eater = context.entity();
        if (eater instanceof Ageable ageable && !ageable.isAdult()) {
            ageable.setAge(Math.min(-1, ageable.getAge() + 40));
            return true;
        }
        if (context.random().nextInt(4) == 0) {
            hurtHeal(eater);
        }
        return true;
    }

    private static void hurtHeal(LivingEntity eater) {
        var attribute = eater.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        double max = attribute == null ? eater.getHealth() : attribute.getValue();
        if (eater.getHealth() < max) {
            eater.setHealth(Math.min(max, eater.getHealth() + 1.0));
        }
    }
}
