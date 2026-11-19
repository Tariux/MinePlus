# Spigot Linkage Hotfix — Root Cause and Follow-up Plan

> Follow-up to `2026-11-19-compat-rewrite-plan.md`. Written from a live server
> log, not from review.

**Goal:** Stop the single jar from linking Paper-only API that compiles against
the stub jars and then throws `NoSuchMethodError` on Spigot, and remove the
classpath condition that allowed it.

**Evidence:** `../server-spigot/logs/latest.log` (Spigot 1.21.1, CraftBukkit
4344), 1009 identical entries and no other error class:

```
java.lang.NoSuchMethodError: 'float org.bukkit.entity.LivingEntity.getPitch()'
    at com.mineplus.infrastructure.mob.custom.MobRenderEngine.animate(MobRenderEngine.java:685)
    at com.mineplus.infrastructure.mob.custom.MobRenderEngine.tick(MobRenderEngine.java:578)
```

## Root cause

`build.gradle` listed `libs/paper-api-1.21.jar` **before**
`libs/spigot-api-1.21.1.jar` on the compile classpath. Paper is a superset of
Spigot, so any Paper-only method compiled silently and linked fine on Paper —
then failed at the first call on Spigot. This is the exact failure mode the
version-compat design was written to prevent, reintroduced by the classpath
order rather than by a missing probe.

`MobRenderEngine.animate()` is the whole pose pass. It threw at line 685 on
**every tick**, before any transform was pushed. Consequences map one-to-one to
the reported symptoms:

| Symptom | Cause |
| --- | --- |
| Rig renders but is stuck in the ground | Displays keep their spawn position/identity transform; `animate` never poses them |
| Rigged mob does not move while the base mob does | Same — the base entity is server-driven, the rig is frozen |
| Visible base mob under a rig | `syncBasePresentation` runs after the throw, so the base is never hidden |
| Purple/black box | Display item stacks were created, but nothing ever re-poses them; a viewer without the applied pack sees the missing model |

## The five offenders (found by compiling against the Spigot stub only)

| File | Paper-only symbol | Fix |
| --- | --- | --- |
| `mob/custom/MobRenderEngine.java` | `LivingEntity#getPitch()` | `getLocation().getPitch()` |
| `virtual/animation/ModelAnimationManager.java` | `Entity#getX()/getZ()` | `getLocation().getX()/getZ()` |
| `util/SchedulerCompat.java` | `ScheduledTask`, `Bukkit#getGlobalRegionScheduler`, `Player#getScheduler` | reflective `FoliaBridge`, resolved only when `RegionizedServer` is present |
| `mob/navigation/PaperPathfinderBackend.java` | `Mob#getPathfinder()` | reflective `Bridge`, `available()` false when absent |
| `input/HeldItemInput.java` | `PlayerStopUsingItemEvent` | reflective `registerEvent` + `EventExecutor`, no import of the type |

`examples/mineplus-fun` had the same class of bug: `CabinetStore` used
`ItemStack#serializeAsBytes`/`deserializeBytes` (Paper-only), which would have
thrown the first time a cabinet saved. It now uses `BukkitObjectOutputStream`/
`BukkitObjectInputStream` over Base64.

## Tasks

- [x] **T1: Reproduce at compile time.** Remove `paper-api-1.21.jar` from the
      compile classpath and compile `src/main/java` with `javac --release 21`.
      Five files fail; nothing else does.
- [x] **T2: Fix the two accessor bugs** (`MobRenderEngine`,
      `ModelAnimationManager`).
- [x] **T3: Make `SchedulerCompat` fully reflective.** `FoliaBridge` caches the
      `GlobalRegionScheduler`/`EntityScheduler`/`ScheduledTask` methods and is
      resolved only when the Folia class probe succeeds. Spigot path unchanged.
- [x] **T4: Make `PaperPathfinderBackend` fully reflective.** `Bridge` caches
      `getPathfinder`, `setCanOpenDoors/PassDoors/Float`, `moveTo`, `findPath`,
      `canReachFinalPoint`, `stopPathfinding`.
- [x] **T5: Make the Paper stop-using seam reflective.** Register a custom
      `EventExecutor` against the event class by name; drop the direct import
      and the probe-gated subclass.
- [x] **T6: Fix `CabinetStore`.** Portable Bukkit object stream instead of
      Paper's byte pair.
- [x] **T7: Remove `paper-api-1.21.jar` from all four build files** (Core +
      three example modules) and document why. The absence of the jar *is* the
      guardrail: any future Paper-only call fails the build.
- [x] **T8: Verify.** `javac` clean for Core, tests and all three modules
      against Spigot-only. `gradle build -q` produces the jar; all five test
      runners pass. `javap` on the built jar confirms no
      `LivingEntity.getPitch`, no direct `getGlobalRegionScheduler`, and only
      reflective pathfinder fields.

## Invariant going forward

The compile classpath contains **Spigot stubs only**. Paper and Folia
capabilities are reached through a probe plus cached `Method`/`MethodHandle`,
in the owning seam (`SchedulerCompat`, `PaperPathfinderBackend`,
`ItemFrameWriter`, `PackItemFactory`). Never add `paper-api` back to a build
file to make a Paper-only call compile — add the probe instead.
