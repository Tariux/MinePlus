package com.mineplus.infrastructure.registry;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/**
 * The Core's magazine model: a countable resource carried by an item stack,
 * authoritative in memory while that stack is in use and persisted to its PDC
 * when it leaves the hand.
 *
 * <p>Why not simply write the stack every shot: a held item's components are
 * part of its client-side identity. Rewrite them per shot and the client sees a
 * different item in the same slot, which replays the equip animation, aborts any
 * in-progress use gesture, and sends a full slot packet 20 times a second per
 * shooter. The deferred model costs one silent PDC write at the moments the item
 * actually leaves the hand, and the live value rides in memory in between.
 *
 * <p>Stacks are matched by a per-stack identity tag
 * ({@link ItemState#identityTag(Plugin)}) rather than by slot, so a magazine
 * survives re-ordering, a hop to the off hand, or a re-log. The tag is only
 * stamped on single-item stacks: a stack of two is a factory-fresh pair of
 * clones sharing one tag, and letting them share one magazine would let firing
 * one silently spend the other's rounds.
 */
public final class ItemMagazine {

    private final ItemState roundsSlot;
    private final ItemState identity;

    public ItemMagazine(Plugin plugin, String slotName) {
        this.roundsSlot = new ItemState(plugin, slotName);
        this.identity = ItemState.identityTag(plugin);
    }

    /**
     * The live magazine of one stack. The count starts from the stack's PDC (or
     * the weapon's capacity when the stack carries no tag yet) and then lives in
     * memory until {@link ItemMagazine#flush} writes it back.
     */
    public static final class Live {
        private final String uid;
        private final int capacity;
        private int rounds;

        Live(String uid, int capacity, int rounds) {
            this.uid = uid;
            this.capacity = capacity;
            this.rounds = Math.max(0, Math.min(capacity, rounds));
        }

        /** The identity tag of the stack this magazine belongs to. */
        public String uid() {
            return uid;
        }

        public int capacity() {
            return capacity;
        }

        public int rounds() {
            return rounds;
        }

        public boolean empty() {
            return rounds <= 0;
        }

        public boolean full() {
            return rounds >= capacity;
        }

        /** Spends one round. */
        public void spend() {
            rounds = Math.max(0, rounds - 1);
        }

        /** Spends {@code count} rounds. */
        public void spend(int count) {
            rounds = Math.max(0, rounds - Math.max(0, count));
        }

        /** Adds rounds, clamped at the capacity. */
        public int add(int delta) {
            rounds = Math.max(0, Math.min(capacity, rounds + Math.max(0, delta)));
            return rounds;
        }

        public void refill() {
            rounds = capacity;
        }

        /** Sets the count directly (a module's own reload economy). */
        public void rounds(int value) {
            rounds = Math.max(0, Math.min(capacity, value));
        }
    }

    /**
     * Opens the live magazine of {@code stack}, stamping an identity tag when the
     * stack is single and untagged.
     *
     * @param capacity the maximum the weapon holds; {@code 0} means the item
     *                 carries no countable resource
     */
    public Live open(ItemStack stack, int capacity) {
        int cap = Math.max(0, capacity);
        if (stack == null || stack.getType().isAir() || cap <= 0) {
            return new Live(null, cap, 0);
        }
        String uid = stack.getAmount() == 1 ? identity.readString(stack) : null;
        int stored = roundsSlot.get(stack, cap);
        if (uid == null || uid.isBlank()) {
            if (stack.getAmount() != 1) {
                // Untaggable stack: it keeps its own count only for this session.
                return new Live(null, cap, stored);
            }
            uid = identity.getOrCreateIdentity(stack);
        }
        return new Live(uid, cap, Math.min(cap, Math.max(0, stored)));
    }

    /**
     * Writes a live magazine back to the stack it belongs to, anywhere in the
     * player's inventory. Silent when the stack is gone (dropped into lava,
     * consumed on death): a lost magazine is not worth an error, and the item is
     * already out of play.
     *
     * @return {@code true} when the owning stack was found and updated
     */
    public boolean flush(Player player, Live magazine) {
        if (player == null || magazine == null || magazine.uid == null) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack cursor = player.getItemOnCursor();
        ItemStack[] carried = {
                inventory.getItemInMainHand(), inventory.getItemInOffHand(), cursor
        };
        for (ItemStack candidate : carried) {
            if (matches(candidate, magazine.uid)) {
                roundsSlot.set(candidate, magazine.rounds);
                if (candidate == cursor) {
                    // The cursor stack is a detached clone: hand it back so the
                    // write actually lands.
                    player.setItemOnCursor(candidate);
                }
                return true;
            }
        }
        for (ItemStack candidate : inventory.getContents()) {
            if (matches(candidate, magazine.uid)) {
                roundsSlot.set(candidate, magazine.rounds);
                return true;
            }
        }
        return false;
    }

    /** The identity tag of a stack, or {@code null} when it carries none. */
    public String uid(ItemStack stack) {
        String uid = identity.readString(stack);
        return uid == null || uid.isBlank() ? null : uid;
    }

    /**
     * Moves one stack's identity onto another (an arsenal migration that
     * rebuilds the item but must keep its magazine). Silent when the source
     * carries no identity.
     */
    public void transferUid(ItemStack from, ItemStack to) {
        if (from == null || to == null) {
            return;
        }
        String uid = uid(from);
        if (uid != null) {
            identity.setString(to, uid);
        }
    }

    /** The persisted count of a stack, or {@code capacity} when it carries no tag. */
    public int rounds(ItemStack stack, int capacity) {
        if (stack == null || stack.getType().isAir() || capacity <= 0) {
            return 0;
        }
        return Math.min(capacity, Math.max(0, roundsSlot.get(stack, capacity)));
    }

    /**
     * Writes the count straight onto a stack (shop restock, commands, crafting a
     * loaded weapon). The held-stack path goes through {@link #open} instead, so
     * the in-memory value stays authoritative while the item is in use.
     */
    public void setRounds(ItemStack stack, int rounds, int capacity) {
        if (stack == null || stack.getType().isAir() || capacity <= 0) {
            return;
        }
        roundsSlot.set(stack, Math.max(0, Math.min(capacity, rounds)));
    }

    /** Gives a freshly issued stack a full magazine and an identity tag. */
    public Live issue(ItemStack stack, int capacity) {
        setRounds(stack, capacity, capacity);
        return open(stack, capacity);
    }

    private boolean matches(ItemStack candidate, String uid) {
        if (candidate == null || candidate.getType().isAir()) {
            return false;
        }
        String candidateUid = identity.readString(candidate);
        return candidateUid != null && candidateUid.equals(uid);
    }
}
