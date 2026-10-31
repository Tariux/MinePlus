package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.MobBehaviorApi;
import com.mineplus.infrastructure.mob.MobIdentityResolver;
import com.mineplus.infrastructure.mob.MobProfile;
import com.mineplus.infrastructure.mob.navigation.MobAiIsolation;
import com.mineplus.pack.PackApi;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The Core's implementation of {@link CustomMobApi}: everything that makes a
 * custom mob a custom mob rather than a weird vanilla entity.
 *
 * <p>Owns the identity (PDC + discovery tag), the owner-scoped definition
 * registry, spawning and chunk restore, vanilla AI isolation, behavior profile
 * registration (bound to the custom id, never to the base type) and the visual
 * rig lifecycle. Modules only describe a {@link CustomMobDefinition}.</p>
 *
 * <p>Vanilla entities are never touched: identity is only ever written by this
 * manager, and a vanilla mob resolves to {@code null}, which leaves the
 * behavior engine on ordinary type dispatch. A custom mob resolves to its id,
 * whose profiles are the only ones the engine consults for it.</p>
 *
 * <p>Thread model: registration, spawning and entity events are main-thread
 * operations. Identity lookups from the engine's sweep are reads of a
 * concurrent map.</p>
 */
public final class CustomMobManager implements CustomMobApi, MobIdentityResolver, Listener {

    /** PDC key holding the custom id; the authoritative identity (survives restarts). */
    private static final String IDENTITY_KEY = "custom_mob_id";
    /** Scoreboard tag prefix, so an entity is discoverable without a PDC read. */
    private static final String TAG_PREFIX = "mineplus_custom_mob:";
    /** How often isolation is re-asserted on live custom mobs (ticks). */
    private static final long REASSERT_INTERVAL_TICKS = 100L;
    /**
     * A definition without a behavior consumer still gets a profile: an idle
     * action whose only job is to make "this mob has a Core behavior profile"
     * true (and visible in telemetry) while doing nothing. The mob stands
     * still because vanilla AI is cleared, not because the engine is silent by
     * accident.
     */
    private static final BehaviorAction IDLE_ACTION = new BehaviorAction() {
        @Override
        public String id() {
            return "custom_mob_idle";
        }

        @Override
        public boolean execute(BehaviorContext context) {
            return false;
        }
    };

    private final Plugin plugin;
    private final PackApi pack;
    private final MobBehaviorApi behaviorApi;
    private final NamespacedKey identityKey;
    private final MobRenderEngine renderEngine;
    /** Definition id -> registration (insertion ordered for status output). */
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    /** Owner -> the definition ids it registered. */
    private final Map<Plugin, Set<String>> ownedIds = new ConcurrentHashMap<>();
    /** Live custom entities: UUID -> definition id. The identity cache the engine reads. */
    private final Map<UUID, String> identities = new ConcurrentHashMap<>();
    /** Entity -> how its vanilla AI was last actually cleared, for status output. */
    private final Map<UUID, MobAiIsolation.Result> isolationResults = new ConcurrentHashMap<>();
    /** Re-asserts isolation on live custom mobs. */
    private org.bukkit.scheduler.BukkitTask reassertTask;
    /** One full warning per session about an unreachable isolation surface. */
    private boolean hasWarnedUnreachable;

    private volatile CustomMobSettings settings;
    private boolean started;

    public CustomMobManager(Plugin plugin, PackApi pack, MobBehaviorApi behaviorApi,
                            CustomMobSettings settings) {
        this.plugin = plugin;
        this.pack = pack;
        this.behaviorApi = behaviorApi;
        this.identityKey = new NamespacedKey(plugin, IDENTITY_KEY);
        this.settings = settings == null ? CustomMobSettings.defaults() : settings;
        this.renderEngine = new MobRenderEngine(plugin, pack, this.settings);
    }

    /**
     * Late transport injection: the packet-based display transport may start
     * before or after the mob engine, so the Core hands it over once both exist.
     */
    public void attachTransport(com.mineplus.infrastructure.virtual.display.DisplayTransport transport) {
        renderEngine.setTransport(transport);
    }

    /**
     * Installs the event listeners. Idempotent; called once by the Core's
     * bootstrap, so chunk restores work before any module registers a
     * definition.
     */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        renderEngine.start();
        armReassertSweep();
    }

    /**
     * Vanilla goals come back at runtime — a villager that panics, a mob that
     * is tempted with food — so isolation is re-asserted on the live custom
     * mobs instead of being applied once at spawn. The sweep is cheap: it walks
     * the identity cache and touches only entities that are still loaded.
     */
    private void armReassertSweep() {
        if (reassertTask != null) {
            return;
        }
        reassertTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reassertIsolation,
                REASSERT_INTERVAL_TICKS, REASSERT_INTERVAL_TICKS);
    }

    private void reassertIsolation() {
        if (!settings.enabled() || identities.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : identities.entrySet()) {
            if (!(Bukkit.getEntity(entry.getKey()) instanceof LivingEntity living)
                    || living.isDead() || !living.isValid() || !(living instanceof Mob mob)) {
                isolationResults.remove(entry.getKey());
                identities.remove(entry.getKey());
                continue;
            }
            Registration registration = registrations.get(entry.getValue());
            if (registration == null) {
                continue; // unknown id: restoreIfCustom owns it when its module returns
            }
            if (registration.definition.keepsVanillaGoals()) {
                continue;
            }
            MobAiIsolation.Result result = MobAiIsolation.isolateDetailed(mob);
            if (!result.isolated() && !result.equals(isolationResults.get(entry.getKey()))) {
                reportIsolationFailure(registration.definition, living, result);
            }
            isolationResults.put(entry.getKey(), result);
        }
    }

    // ------------------------------------------------------------------
    // CustomMobApi — registration
    // ------------------------------------------------------------------

    @Override
    public boolean isAvailable() {
        return settings.enabled();
    }

    @Override
    public void register(Plugin owner, CustomMobDefinition definition) {
        if (!settings.enabled() || owner == null || definition == null) {
            return;
        }
        Registration existing = registrations.get(definition.id());
        if (existing != null) {
            if (!existing.owner.equals(owner)) {
                throw new IllegalStateException("Custom mob '" + definition.id() + "' is already registered by "
                        + existing.owner.getName() + " (attempted by " + owner.getName() + ")");
            }
            if (existing.definition == definition) {
                return;
            }
            unregister(owner, definition.id());
        }

        MobProfile profile = buildProfile(definition);
        behaviorApi.register(owner, profile);
        Registration registration = new Registration(owner, definition, profile);
        registrations.put(definition.id(), registration);
        ownedIds.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(definition.id());

        // The engine owns presentation: it compiles the bundle or retexture
        // assets and (re)builds the sessions of this id's live mobs through the
        // restore pass below.
        renderEngine.registerDefinition(definition);
        restoreLoadedEntities();
        if (DebugLogger.isEnabled()) {
            plugin.getLogger().info("[custom-mobs] " + owner.getName() + " registered '"
                    + definition.id() + "' (" + definition.baseType()
                    + (definition.renderSpec() == null ? ", legacy model path" : ", "
                            + definition.renderSpec().type())
                    + ")");
        }
    }

    private MobProfile buildProfile(CustomMobDefinition definition) {
        MobProfile.Builder builder = MobProfile.builder(definition.baseType(), definition.id());
        Consumer<MobProfile.Builder> behavior = definition.behavior();
        if (behavior == null) {
            builder.action(IDLE_ACTION, 30.0);
        } else {
            behavior.accept(builder);
        }
        return builder.build();
    }

    @Override
    public void unregister(Plugin owner, String definitionId) {
        if (owner == null || definitionId == null) {
            return;
        }
        String id = definitionId.toLowerCase(Locale.ROOT);
        Registration registration = registrations.get(id);
        if (registration == null || !registration.owner.equals(owner)) {
            return;
        }
        registrations.remove(id);
        behaviorApi.unregister(owner, registration.profile);
        Set<String> owned = ownedIds.get(owner);
        if (owned != null) {
            owned.remove(id);
        }
        renderEngine.unregisterDefinition(id);
        removeLiveMobs(id);
    }

    @Override
    public void unregisterAll(Plugin owner) {
        if (owner == null) {
            return;
        }
        Set<String> owned = ownedIds.remove(owner);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        for (String id : new ArrayList<>(owned)) {
            unregister(owner, id);
        }
    }

    @Override
    public CustomMobDefinition definition(String definitionId) {
        Registration registration = definitionId == null
                ? null : registrations.get(definitionId.toLowerCase(Locale.ROOT));
        return registration == null ? null : registration.definition;
    }

    @Override
    public List<CustomMobDefinition> definitions() {
        List<CustomMobDefinition> out = new ArrayList<>(registrations.size());
        for (Registration registration : registrations.values()) {
            out.add(registration.definition);
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public boolean isRegistered(String definitionId) {
        return definitionId != null && registrations.containsKey(definitionId.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // CustomMobApi — spawning and live mobs
    // ------------------------------------------------------------------

    @Override
    public LivingEntity spawn(Location location, String definitionId) {
        return spawn(location, definitionId, null);
    }

    @Override
    public LivingEntity spawn(Location location, String definitionId, Consumer<LivingEntity> configurator) {
        if (!settings.enabled() || location == null || location.getWorld() == null || definitionId == null) {
            return null;
        }
        Registration registration = registrations.get(definitionId.toLowerCase(Locale.ROOT));
        if (registration == null) {
            return null;
        }
        World world = location.getWorld();
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            try {
                world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
            } catch (Throwable cannotLoad) {
                return null;
            }
        }
        Entity spawned;
        try {
            spawned = world.spawnEntity(location, registration.definition.baseType());
        } catch (Throwable refused) {
            return null;
        }
        if (!(spawned instanceof LivingEntity living) || !(spawned instanceof Mob)) {
            spawned.remove();
            return null;
        }
        applyDefinition(living, registration.definition, true);
        try {
            if (configurator != null) {
                configurator.accept(living);
            }
            Consumer<LivingEntity> lastWord = registration.definition.spawnConfigurator();
            if (lastWord != null) {
                lastWord.accept(living);
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("[custom-mobs] spawn configurator for '" + registration.definition.id()
                    + "' threw: " + throwable);
        }
        return living;
    }

    /**
     * Applies the whole definition to a live entity: identity, attributes,
     * flags, isolation and the rig. Shared by spawn and chunk restore — the
     * only difference is that a restore keeps the entity's current health.
     */
    private void applyDefinition(LivingEntity entity, CustomMobDefinition definition, boolean fresh) {
        identities.put(entity.getUniqueId(), definition.id());
        entity.getPersistentDataContainer().set(identityKey, PersistentDataType.STRING, definition.id());
        tagIdentity(entity, definition.id());

        definition.attributes().applyTo(entity, fresh);
        if (entity instanceof Mob mob) {
            mob.setCanPickupItems(definition.canPickupItems());
            mob.setPersistent(definition.persistent());
            mob.setCustomName(definition.displayName());
            mob.setCustomNameVisible(definition.showName());
            if (definition.keepsVanillaGoals()) {
                // The author asked for the donor's own AI, so vanilla goals
                // staying put is the intended behavior, not a failed isolation.
                isolationResults.put(entity.getUniqueId(), MobAiIsolation.Result.GOALS_KEPT);
            } else {
                MobAiIsolation.Result result = MobAiIsolation.isolateDetailed(mob);
                isolationResults.put(entity.getUniqueId(), result);
                if (!result.isolated()) {
                    reportIsolationFailure(definition, entity, result);
                }
            }
        }

        // The engine owns base visibility: it hides the base per viewer when it
        // presents the mob and shows it when it cannot. A mob saved by an older
        // version may carry a persisted invisible flag; clear it whenever no rig
        // ended up covering the entity.
        renderEngine.onSpawned(entity, definition);
        if (!renderEngine.hasRig(entity)) {
            entity.setInvisible(false);
        }
    }

    /**
     * Says out loud what failed and what to do about it. A mob whose selectors
     * and Brain are both unreachable keeps its vanilla AI — the mob is then a
     * normal vanilla creature wearing a custom skin, which is a visible defect,
     * never something to swallow silently.
     */
    private void reportIsolationFailure(CustomMobDefinition definition, LivingEntity entity,
                                        MobAiIsolation.Result result) {
        String detail = MobAiIsolation.isSupported(entity)
                ? "the reflection surface changed on this runtime"
                : "'" + entity.getType() + "' exposes neither goal selectors nor a Brain";
        if (!hasWarnedUnreachable) {
            hasWarnedUnreachable = true;
            plugin.getLogger().warning("[custom-mobs] Could not isolate '" + definition.id() + "' ("
                    + entity.getType() + "): " + detail + "; vanilla AI keeps running for it. ("
                    + result.label() + ")");
        } else {
            DebugLogger.warning("[custom-mobs] Could not isolate '" + definition.id() + "': " + detail);
        }
    }

    private void tagIdentity(Entity entity, String id) {
        String tag = TAG_PREFIX + id;
        if (!entity.getScoreboardTags().contains(tag)) {
            entity.addScoreboardTag(tag);
        }
    }

    @Override
    public boolean isCustomMob(Entity entity) {
        return customMobId(entity) != null;
    }

    @Override
    public String customMobId(LivingEntity entity) {
        return customMobId((Entity) entity);
    }

    @Override
    public String customMobId(Entity entity) {
        if (entity == null) {
            return null;
        }
        String cached = identities.get(entity.getUniqueId());
        if (cached != null) {
            return cached;
        }
        String stored = entity.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
        if (stored == null) {
            return null;
        }
        String id = stored.toLowerCase(Locale.ROOT);
        identities.put(entity.getUniqueId(), id);
        return id;
    }

    @Override
    public int liveCount(String definitionId) {
        return liveMobs(definitionId).size();
    }

    @Override
    public List<LivingEntity> liveMobs(String definitionId) {
        if (definitionId == null) {
            return List.of();
        }
        String id = definitionId.toLowerCase(Locale.ROOT);
        List<LivingEntity> mobs = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : identities.entrySet()) {
            if (!entry.getValue().equals(id)) {
                continue;
            }
            if (Bukkit.getEntity(entry.getKey()) instanceof LivingEntity living
                    && living.isValid() && !living.isDead()) {
                mobs.add(living);
            }
        }
        return mobs;
    }

    @Override
    public int removeLiveMobs(String definitionId) {
        int removed = 0;
        for (LivingEntity mob : liveMobs(definitionId)) {
            mob.remove();
            removed++;
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Chunk restore and identity lifecycle
    // ------------------------------------------------------------------

    /**
     * Restores custom mobs as chunks load: identity, attributes (health
     * preserved), isolation and rig come back, and an entity whose definition
     * is missing is removed when {@link CustomMobSettings#removeUnknownOnLoad()}.
     *
     * <p>Unknown-entity removal is only armed while at least one definition is
     * registered: during startup, chunks load before modules enable, and
     * deleting every unregistered custom tag would delete another module's
     * mobs before it had a chance to register them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!settings.enabled()) {
            return;
        }
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity living) {
                restoreIfCustom(living);
            }
        }
    }

    private void restoreIfCustom(LivingEntity entity) {
        String id;
        try {
            id = entity.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
        } catch (Throwable unreadable) {
            return;
        }
        if (id == null) {
            return;
        }
        id = id.toLowerCase(Locale.ROOT);
        Registration registration = registrations.get(id);
        if (registration == null) {
            if (settings.removeUnknownOnLoad() && !registrations.isEmpty()) {
                DebugLogger.info("[custom-mobs] Removing '" + id + "' " + entity.getType()
                        + ": its definition is not registered.");
                identities.remove(entity.getUniqueId());
                entity.remove();
            } else {
                // Keep it alive but do not let vanilla AI take over: it becomes
                // fully custom again the moment its module registers.
                identities.put(entity.getUniqueId(), id);
                if (entity instanceof Mob mob) {
                    isolationResults.put(entity.getUniqueId(), MobAiIsolation.isolateDetailed(mob));
                }
            }
            return;
        }
        applyDefinition(entity, registration.definition, false);
    }

    /**
     * Sweep of already-loaded entities on registration. Worlds and their spawn
     * chunks load before plugins enable, so without this pass a custom mob
     * standing in a spawn chunk would never be restored until its chunk
     * reloaded. Only known ids are restored — an unknown id may belong to a
     * module that has not enabled yet — and the sweep runs on every
     * registration so a module that enables after another still picks up its
     * own mobs.
     */
    private void restoreLoadedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                String stored;
                try {
                    stored = entity.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
                } catch (Throwable unreadable) {
                    continue;
                }
                if (stored == null) {
                    continue;
                }
                Registration registration = registrations.get(stored.toLowerCase(Locale.ROOT));
                if (registration != null) {
                    applyDefinition(living, registration.definition, false);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        forget(event.getEntity().getUniqueId());
    }

    private void forget(UUID entityId) {
        // The render engine owns its sessions (death clip, unload cleanup via
        // its own listeners and tick pass); identity bookkeeping only.
        isolationResults.remove(entityId);
        identities.remove(entityId);
    }

    // ------------------------------------------------------------------
    // Rendering, settings, shutdown
    // ------------------------------------------------------------------

    @Override
    public void refreshRendering() {
        if (!settings.enabled()) {
            return;
        }
        renderEngine.updateSettings(settings);
        renderEngine.refreshRendering();
    }

    @Override
    public void updateSettings(CustomMobSettings next) {
        this.settings = next == null ? CustomMobSettings.defaults() : next;
        renderEngine.updateSettings(this.settings);
    }

    @Override
    public void shutdown() {
        if (reassertTask != null) {
            reassertTask.cancel();
            reassertTask = null;
        }
        renderEngine.shutdown();
        identities.clear();
        isolationResults.clear();
        registrations.clear();
        ownedIds.clear();
    }

    /** Live rig session count, for the Core's status command. */
    public int riggedCount() {
        return renderEngine.sessionCount();
    }

    /** The compiled bundle of one definition, for the inspect command. */
    public com.mineplus.infrastructure.mob.custom.render.ModelBundle bundle(String definitionId) {
        return renderEngine.bundle(definitionId);
    }

    /**
     * How this mob's vanilla AI was last cleared, for a status line; {@code null}
     * for an entity the manager has never applied a definition to.
     */
    public MobAiIsolation.Result isolationResult(LivingEntity entity) {
        return entity == null ? null : isolationResults.get(entity.getUniqueId());
    }

    /** The last pose a rigged mob was given, or {@code null} when it carries no rig. */
    public String poseSnapshot(UUID mobId) {
        return mobId != null && Bukkit.getEntity(mobId) instanceof LivingEntity living
                ? renderEngine.poseSnapshot(living) : null;
    }

    @Override
    public String poseSnapshot(LivingEntity mob) {
        return mob == null ? null : renderEngine.poseSnapshot(mob);
    }

    @Override
    public boolean playAttack(LivingEntity mob) {
        return renderEngine.playAttack(mob);
    }

    @Override
    public boolean playAnimation(LivingEntity mob, String clipName) {
        return renderEngine.playAnimation(mob, clipName);
    }

    /** The bone-marker debug mode (engine-level, for the mob command). */
    public boolean boneDebug() {
        return renderEngine.boneDebug();
    }

    /** Shows or hides a tiny cube at every bone pivot of every rigged mob. */
    public void setBoneDebug(boolean on) {
        renderEngine.setBoneDebug(on);
    }

    @Override
    public String isolationLabel(LivingEntity mob) {
        MobAiIsolation.Result result = isolationResult(mob);
        return result == null ? null : result.label();
    }

    @Override
    public String rigFailure(String definitionId) {
        return renderEngine.rigFailure(definitionId);
    }

    @Override
    public String rigStatus(String definitionId) {
        return renderEngine.rigStatus(definitionId);
    }

    private static final class Registration {
        private final Plugin owner;
        private final CustomMobDefinition definition;
        private final MobProfile profile;

        private Registration(Plugin owner, CustomMobDefinition definition, MobProfile profile) {
            this.owner = owner;
            this.definition = definition;
            this.profile = profile;
        }
    }
}
