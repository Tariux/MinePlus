package com.mineplus.infrastructure.fx.unit;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * One owner for composed visual effects: units are admitted under a per-tick
 * budget, advanced by a single task and released together.
 *
 * <p><b>Disabled by default.</b> With {@code VISUAL_UNITS.ENABLED: false} no
 * task exists, {@link #spawn} and {@link #impact} return before allocating and
 * every module that emits particles or sounds directly is untouched.</p>
 *
 * <p><b>The budget.</b> At most {@code MAX_ACTIVE} units are live, at most
 * {@code MAX_SPAWN_PER_TICK} are admitted per tick, and the advance pass stops
 * at {@code MAX_MS_PER_TICK} (the remainder waits for the next tick). When a
 * newcomer arrives over the active ceiling, the lowest-priority unit is
 * dropped — and the newcomer itself when it is the lowest-priority one — so a
 * decorative impact never starves the effect a player needs to read.</p>
 *
 * <p><b>Displays are always returned.</b> A decal's display is released on
 * expiry, on budget drop and on {@link #shutdown()}, and the live-decal count
 * is exposed in {@link Stats} so a leak is visible rather than silent.</p>
 */
public final class VisualUnitService {

    /**
     * @param spawned        units admitted since startup
     * @param completed      units that reached their lifetime or finished early
     * @param dropped        units/components refused by the budget
     * @param activeUnits    units alive right now
     * @param liveDecals     pooled decal displays currently attached
     */
    public record Stats(long spawned, long completed, long dropped, int activeUnits, int liveDecals) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0);
    }

    private static final class Active {
        private final VisualUnit unit;
        private final Location location;
        private final List<VisualComponent> live;
        private int elapsed;

        private Active(VisualUnit unit, Location location, List<VisualComponent> live) {
            this.unit = unit;
            this.location = location;
            this.live = live;
        }
    }

    private final Plugin plugin;
    private final ParticleFx particles;
    private final VirtualBlockManager virtualBlockManager;
    private final List<Active> active = new ArrayList<>();
    private final Set<UUID> decals = new HashSet<>();

    private volatile VisualUnitSettings settings = VisualUnitSettings.defaults();
    private Location currentAnchor;
    private boolean running;
    private BukkitTask tickTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask;
    private long spawned;
    private long completed;
    private long dropped;

    public VisualUnitService(Plugin plugin, ParticleFx particles, VirtualBlockManager virtualBlockManager) {
        this.plugin = plugin;
        this.particles = particles;
        this.virtualBlockManager = virtualBlockManager;
    }

    /** Applies the {@code VISUAL_UNITS} slice (enable/reload); disabling releases everything. */
    public void updateSettings(VisualUnitSettings settings) {
        this.settings = settings == null ? VisualUnitSettings.defaults() : settings;
        if (!this.settings.enabled()) {
            shutdown();
        }
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public VisualUnitSettings settings() {
        return settings;
    }

    public ParticleFx particles() {
        return particles;
    }

    /** The running display transport, or {@code null} when the packet path is unavailable. */
    public DisplayTransport transport() {
        if (virtualBlockManager == null) {
            return null;
        }
        DisplayTransport transport = virtualBlockManager.displayTransport();
        return transport != null && transport.isRunning() ? transport : null;
    }

    /** The anchor of the unit currently being started or advanced. */
    public Location anchorOfCurrent() {
        return currentAnchor;
    }

    /**
     * Admits one composed unit.
     *
     * @return {@code true} when it was admitted; {@code false} when disabled,
     *         off-thread, empty or refused by the budget
     */
    public boolean spawn(VisualUnit unit) {
        if (!settings.enabled() || unit == null || unit.components().isEmpty()) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            dropped++;
            DebugLogger.warning("[VisualUnits] spawn refused: not on the main thread.");
            return false;
        }
        if (spawnsThisTick >= settings.maxSpawnPerTick()) {
            dropped++;
            return false;
        }
        if (active.size() >= settings.maxActive()) {
            Active victim = lowestPriority();
            if (victim == null || victim.unit.priority() >= unit.priority()) {
                dropped++;
                return false;
            }
            drop(victim);
        }
        Location place = resolve(unit);
        if (place == null) {
            dropped++;
            return false;
        }
        List<VisualComponent> live = new ArrayList<>(unit.components());
        Active entry = new Active(unit, place, live);
        active.add(entry);
        spawned++;
        spawnsThisTick++;
        currentAnchor = place;
        for (VisualComponent component : live) {
            startQuietly(component);
        }
        startTask();
        return true;
    }

    /**
     * The one convenience a hit path needs: flash + sparks + dust + surface
     * decal + sound as a single unit with one lifetime and one budget.
     */
    public boolean impact(Location at, ImpactProfile profile) {
        if (!settings.enabled() || at == null || at.getWorld() == null || profile == null) {
            return false;
        }
        VisualUnit.Builder builder = VisualUnit.builder(at)
                .lifetime(profile.lifetimeTicks())
                .priority(profile.priority());
        if (profile.decal() != null && !profile.decal().isAir()) {
            builder.component(new DecalDisplay(profile.decal(), profile.decalScale(), profile.priority()));
        }
        if (profile.flash() != null) {
            builder.component(new LightFlash(profile.flash(), 3, profile.priority()));
        }
        if (profile.sparks() != null) {
            builder.component(new ParticleBurst(ParticleFx.Tier.CRITICAL, profile.sparks(),
                    5, 0.05, 0.05, 0.05, 0.09, profile.priority()));
        }
        if (profile.dust() != null) {
            builder.component(new ParticleBurst(ParticleFx.Tier.AMBIENT, profile.dust(),
                    5, 0.20, 0.20, 0.20, 0.0, profile.priority()));
        }
        if (profile.sound() != null && !profile.sound().isBlank()) {
            builder.component(new SoundCue(profile.sound(), profile.volume(), profile.pitch(),
                    profile.fallbackSound(), profile.priority()));
        }
        return spawn(builder.build());
    }

    /** Live-decal bookkeeping (called by {@link DecalDisplay}). */
    public void registerDecal(UUID instanceId) {
        if (instanceId != null) {
            decals.add(instanceId);
        }
    }

    /** Returns a pooled decal display to the transport. Never throws. */
    public void releaseDecal(UUID instanceId) {
        if (instanceId == null || !decals.remove(instanceId)) {
            return;
        }
        DisplayTransport transport = transport();
        if (transport == null) {
            return;
        }
        try {
            transport.removeInstance(instanceId);
        } catch (Throwable failure) {
            DebugLogger.warning("[VisualUnits] could not release decal " + instanceId + ": " + failure);
        }
    }

    public Stats stats() {
        return new Stats(spawned, completed, dropped, active.size(), decals.size());
    }

    /**
     * Releases every live unit, returns every decal display and stops the task.
     * Idempotent; called on disable and whenever the feature is switched off.
     */
    public void shutdown() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
        running = false;
        spawnsThisTick = 0;
        for (Active entry : active) {
            currentAnchor = entry.location;
            for (VisualComponent component : entry.live) {
                cancelQuietly(component);
            }
            entry.live.clear();
        }
        active.clear();
        if (!decals.isEmpty()) {
            for (UUID id : new ArrayList<>(decals)) {
                releaseDecal(id);
            }
        }
        currentAnchor = null;
    }

    // ------------------------------------------------------------------ tick

    private int spawnsThisTick;

    private void tick() {
        spawnsThisTick = 0;
        if (!settings.enabled() || active.isEmpty()) {
            stopTask();
            return;
        }
        long deadline = settings.maxMsPerTick() <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + settings.maxMsPerTick() * 1_000_000L;
        Iterator<Active> iterator = active.iterator();
        while (iterator.hasNext()) {
            if (System.nanoTime() > deadline) {
                // Budget spent: the rest of the units wait for the next tick.
                return;
            }
            Active entry = iterator.next();
            currentAnchor = entry.location;
            entry.elapsed++;
            if (entry.elapsed >= entry.unit.lifetimeTicks()) {
                for (VisualComponent component : entry.live) {
                    cancelQuietly(component);
                }
                entry.live.clear();
                iterator.remove();
                completed++;
                continue;
            }
            for (Iterator<VisualComponent> components = entry.live.iterator(); components.hasNext(); ) {
                VisualComponent component = components.next();
                boolean keep;
                try {
                    keep = component.tick(this);
                } catch (Throwable failure) {
                    keep = false;
                    DebugLogger.warning("[VisualUnits] component failed: " + failure);
                }
                if (!keep) {
                    cancelQuietly(component);
                    components.remove();
                }
            }
            if (entry.live.isEmpty()) {
                iterator.remove();
                completed++;
            }
        }
        if (active.isEmpty()) {
            stopTask();
        }
    }

    private void startTask() {
        if (running || plugin == null || !plugin.isEnabled()) {
            return;
        }
        running = true;
        try {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tick(), 1L, 1L);
            } catch (ClassNotFoundException spigot) {
                tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
            }
        } catch (Throwable failure) {
            running = false;
            DebugLogger.warning("[VisualUnits] could not schedule the advance task: " + failure);
        }
    }

    private void stopTask() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
        running = false;
    }

    // ------------------------------------------------------------------ helpers

    private Active lowestPriority() {
        Active lowest = null;
        for (Active entry : active) {
            if (lowest == null || entry.unit.priority() < lowest.unit.priority()) {
                lowest = entry;
            }
        }
        return lowest;
    }

    private void drop(Active entry) {
        currentAnchor = entry.location;
        for (VisualComponent component : entry.live) {
            cancelQuietly(component);
            dropped++;
        }
        entry.live.clear();
        active.remove(entry);
    }

    private Location resolve(VisualUnit unit) {
        UUID anchorId = unit.anchorEntity();
        if (anchorId != null) {
            Entity entity = Bukkit.getEntity(anchorId);
            if (entity != null) {
                return entity.getLocation();
            }
        }
        return unit.location();
    }

    private void startQuietly(VisualComponent component) {
        try {
            component.start(this);
        } catch (Throwable failure) {
            DebugLogger.warning("[VisualUnits] component start failed: " + failure);
        }
    }

    private void cancelQuietly(VisualComponent component) {
        try {
            component.cancel(this);
        } catch (Throwable failure) {
            DebugLogger.warning("[VisualUnits] component cancel failed: " + failure);
        }
    }
}
