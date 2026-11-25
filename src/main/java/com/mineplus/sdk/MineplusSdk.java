package com.mineplus.sdk;

import com.mineplus.infrastructure.core.network.EnergyGrid;
import com.mineplus.infrastructure.core.network.EnergyNetworkService;
import com.mineplus.infrastructure.core.network.KineticGrid;
import com.mineplus.infrastructure.core.network.LogisticsGrid;
import com.mineplus.infrastructure.core.network.MessageBus;
import com.mineplus.infrastructure.core.research.ResearchTree;

/**
 * Extension SDK entry point (mega-plan Phase E): the single object a
 * third-party feature receives to build on the Core. Wraps the stable
 * services behind small interfaces and hides the internal engine.
 *
 * <p>Versioning: {@link #API_VERSION} moves only on a breaking change;
 * minor additions keep the major version. Check it on enable and refuse
 * to load (with a clear log) when the Core is older than the feature
 * needs.
 *
 * @since API 1.0
 */
public final class MineplusSdk {

    /** Major version of this SDK surface. */
    public static final int API_VERSION = 1;

    private final EnergyNetworkService energy;
    private final ResearchTree research;
    private final MessageBus messages;

    /** @hidden constructor is package-private: created by the Core bootstrap. */
    public MineplusSdk(EnergyNetworkService energy, ResearchTree research, MessageBus messages) {
        this.energy = energy;
        this.research = research;
        this.messages = messages;
    }

    /**
     * Guards an extension against a Core that is too old. Returns an
     * error string to log, or null when compatible.
     */
    public static String checkCompatibility(int requiredApiVersion) {
        if (requiredApiVersion > API_VERSION) {
            return "Mineplus Core provides SDK v" + API_VERSION
                    + " but the extension needs v" + requiredApiVersion
                    + " — update the Core first.";
        }
        return null;
    }

    /** Energy networks: register machines, balance, query connectivity. */
    public EnergyNetworkService energy() {
        return energy;
    }

    /** Research tree: define nodes, gate machine crafting on unlocks. */
    public ResearchTree research() {
        return research;
    }

    /** Cross-server messaging (D2): channels, pub/sub, dedupe. */
    public MessageBus messages() {
        return messages;
    }

    /** Grid key packing shared by all networks (same key space). */
    public static long packPosition(int x, int y, int z) {
        return EnergyGrid.pack(x, y, z);
    }

    /** Position unpacking helpers, mirroring {@link #packPosition}. */
    public static int unpackX(long key) {
        return EnergyGrid.unpackX(key);
    }

    public static int unpackY(long key) {
        return EnergyGrid.unpackY(key);
    }

    public static int unpackZ(long key) {
        return EnergyGrid.unpackZ(key);
    }
}
