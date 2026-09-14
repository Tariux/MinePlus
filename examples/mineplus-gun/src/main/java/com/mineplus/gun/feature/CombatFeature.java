package com.mineplus.gun.feature;

import com.mineplus.gun.GunServices;
import com.mineplus.gun.ModuleFeature;
import com.mineplus.gun.input.BukkitInputAdapter;
import com.mineplus.gun.input.InputAdapter;
import com.mineplus.gun.input.PacketInputAdapter;
import com.mineplus.gun.runtime.AmmoModel;
import com.mineplus.gun.runtime.BulletSimulator;
import com.mineplus.gun.runtime.GrenadeRuntime;
import com.mineplus.gun.runtime.GunRuntime;
import com.mineplus.infrastructure.PluginContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Owns the shooting runtime: input, the fire-flow state machine, tick-stepped
 * ballistics, grenades and effects. Selects the input adapter once at enable;
 * weapon logic never branches on it.
 */
public final class CombatFeature extends ModuleFeature {

    private final GunServices services;

    private InputAdapter inputAdapter;
    private GunRuntime runtime;

    public CombatFeature(JavaPlugin plugin, PluginContext context, GunServices services) {
        super(plugin, context);
        this.services = services;
    }

    @Override
    public String id() {
        return "combat";
    }

    @Override
    protected void onEnable() {
        AmmoModel ammo = new AmmoModel(services.itemFactory());
        BulletSimulator simulator = new BulletSimulator(services::config, services.particleFx(), services.soundFx());
        GrenadeRuntime grenades = new GrenadeRuntime(services::config, services.particleFx(), services.soundFx());
        runtime = new GunRuntime(plugin, services, ammo, simulator, grenades);
        services.setRuntime(runtime);

        inputAdapter = packetEventsPresent() ? new PacketInputAdapter(plugin) : new BukkitInputAdapter();
        inputAdapter.register(plugin, runtime);
        runtime.start();

        plugin.getLogger().info("[MineplusGun] Combat runtime started (input=" + inputAdapter.name() + ").");
    }

    @Override
    protected void onDisable() {
        if (inputAdapter != null) {
            inputAdapter.unregister();
            inputAdapter = null;
        }
        if (runtime != null) {
            runtime.stop();
            runtime = null;
        }
    }

    private static boolean packetEventsPresent() {
        return Bukkit.getPluginManager().getPlugin("packetevents") != null
                || Bukkit.getPluginManager().getPlugin("PacketEvents") != null;
    }
}
