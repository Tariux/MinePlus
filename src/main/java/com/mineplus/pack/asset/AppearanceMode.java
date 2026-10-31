package com.mineplus.pack.asset;

import com.mineplus.pack.PackFormat;
import java.util.Locale;

/**
 * How item definitions present a state on the running server
 * ({@code PACK.APPEARANCE_MODE}).
 *
 * <p>{@link #AUTO} is today's behavior and the default: the modern
 * {@code item_model} component on 1.21.4+, legacy {@code custom_model_data}
 * predicates before it. The explicit modes select one appearance strategy for
 * content that wants it, and every mode that needs a capability the server
 * does not have degrades to the closest one it does — never to a broken
 * artifact.</p>
 */
public enum AppearanceMode {

    /** Current behavior on the running server. */
    AUTO,
    /** {@code item_model} definitions where available; legacy predicates otherwise. */
    ITEM_MODEL,
    /** {@code range_dispatch} on {@code custom_model_data} floats inside the item definition. */
    RANGE_DISPATCH,
    /**
     * {@code minecraft:select} on {@code custom_model_data.strings[0]}; falls
     * back to {@link #RANGE_DISPATCH} when the server cannot write the string
     * channel.
     */
    SELECT,
    /** Legacy {@code custom_model_data} predicates on every server version. */
    LEGACY;

    /** The state channel an item definition dispatches on. */
    public enum Dispatch {
        /** The definition names its model directly (framed items still dispatch on floats). */
        DIRECT,
        /** The definition dispatches on {@code custom_model_data} floats. */
        RANGE,
        /** The definition dispatches on {@code custom_model_data} strings. */
        SELECT
    }

    /**
     * @param representation the item representation the compiler and factory use
     * @param dispatch       the state channel the definition JSON uses
     */
    public record Resolved(PackFormat.ItemRepresentation representation, Dispatch dispatch) {
        public boolean modern() {
            return representation == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL;
        }
    }

    public static AppearanceMode fromKey(String key) {
        if (key == null || key.isBlank()) {
            return AUTO;
        }
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "item_model", "item-model", "itemmodel" -> ITEM_MODEL;
            case "range_dispatch", "range-dispatch", "rangedispatch" -> RANGE_DISPATCH;
            case "select" -> SELECT;
            case "legacy", "custom_model_data", "predicates" -> LEGACY;
            default -> AUTO;
        };
    }

    /**
     * Resolves this configured mode against the running server.
     *
     * @param running       the representation the server natively supports
     * @param selectCapable whether the server can serialize the string channel
     *                      ({@code custom_model_data.strings})
     */
    public Resolved resolve(PackFormat.ItemRepresentation running, boolean selectCapable) {
        boolean modern = running == PackFormat.ItemRepresentation.MODERN_ITEM_MODEL;
        return switch (this) {
            case AUTO, ITEM_MODEL -> modern
                    ? new Resolved(running, Dispatch.DIRECT)
                    : legacy();
            case RANGE_DISPATCH -> modern
                    ? new Resolved(running, Dispatch.RANGE)
                    : legacy();
            case SELECT -> modern
                    ? new Resolved(running, selectCapable ? Dispatch.SELECT : Dispatch.RANGE)
                    : legacy();
            case LEGACY -> legacy();
        };
    }

    private static Resolved legacy() {
        return new Resolved(PackFormat.ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA, Dispatch.DIRECT);
    }
}
