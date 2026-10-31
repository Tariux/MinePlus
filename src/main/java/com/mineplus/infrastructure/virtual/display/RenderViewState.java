package com.mineplus.infrastructure.virtual.display;

import org.bukkit.entity.Display;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-observer render state of one rendered instance. Two observers can hold
 * different states of the same model without the transport duplicating it.
 *
 * <p>The LOD tier and forced visibility are acted on by the transport today;
 * the brightness, equipment, stance and animation channels are the observer-side
 * slots modules can set (the stance channel is driven by
 * {@code StanceService}). Instances of this class are main-thread state.</p>
 */
public final class RenderViewState {

    private DisplayTransport.LodTier lodTier;
    private boolean forcedVisible;
    private Display.Brightness brightnessOverride;
    private String stanceId;
    private String animationOverride;
    private final EnumMap<EquipmentSlot, ItemStack> equipmentOverride = new EnumMap<>(EquipmentSlot.class);

    public RenderViewState(DisplayTransport.LodTier lodTier) {
        this.lodTier = lodTier == null ? DisplayTransport.LodTier.HIDDEN : lodTier;
    }

    public DisplayTransport.LodTier lodTier() {
        return lodTier;
    }

    void setLodTier(DisplayTransport.LodTier lodTier) {
        this.lodTier = lodTier == null ? DisplayTransport.LodTier.HIDDEN : lodTier;
    }

    public boolean forcedVisible() {
        return forcedVisible;
    }

    public void setForcedVisible(boolean forcedVisible) {
        this.forcedVisible = forcedVisible;
    }

    public Display.Brightness brightnessOverride() {
        return brightnessOverride;
    }

    public void setBrightnessOverride(Display.Brightness brightnessOverride) {
        this.brightnessOverride = brightnessOverride;
    }

    public String stanceId() {
        return stanceId;
    }

    public void setStanceId(String stanceId) {
        this.stanceId = stanceId;
    }

    public String animationOverride() {
        return animationOverride;
    }

    public void setAnimationOverride(String animationOverride) {
        this.animationOverride = animationOverride;
    }

    /** Unmodifiable view of the observer's equipment override (empty = vanilla equipment). */
    public Map<EquipmentSlot, ItemStack> equipmentOverride() {
        return Collections.unmodifiableMap(equipmentOverride);
    }

    /** Replaces the observer's equipment override; stacks are copied. */
    public void setEquipmentOverride(Map<EquipmentSlot, ItemStack> override) {
        equipmentOverride.clear();
        if (override == null) {
            return;
        }
        for (Map.Entry<EquipmentSlot, ItemStack> entry : override.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            equipmentOverride.put(entry.getKey(), entry.getValue().clone());
        }
    }

    public void clearEquipmentOverride() {
        equipmentOverride.clear();
    }
}
