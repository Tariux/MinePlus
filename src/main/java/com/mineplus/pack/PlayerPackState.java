package com.mineplus.pack;

/**
 * A player's resource-pack state, tracked from join through the client's
 * status callbacks. Pack-dependent features must never assume
 * {@link #APPLIED}; they consult this before choosing their presentation and
 * fall back otherwise (virtual render or vanilla approximation).
 */
public enum PlayerPackState {

    /** No push attempted yet this session (fresh join). */
    UNKNOWN,

    /** The server sent the pack prompt; the client has not answered. */
    REQUESTED,

    /** The client accepted the prompt; download in progress. */
    ACCEPTED,

    /** The player declined the prompt. */
    DECLINED,

    /** The download failed (network, timeout, corrupt artifact). */
    FAILED,

    /** The pack downloaded and was applied successfully. */
    APPLIED;

    /** True when pack-dependent presentation may be used for this player. */
    public boolean hasPack() {
        return this == APPLIED;
    }
}
