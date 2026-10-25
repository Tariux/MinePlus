package com.mineplus.infrastructure.content;

import com.mineplus.pack.item.ItemFrames;
import java.util.List;

/**
 * The presentation facts of one content item — how a
 * {@link ContentItem} looks in the resource pack, in one of exactly two
 * shapes:
 *
 * <ul>
 *   <li><b>Virtual model</b> ({@link #ofVirtual}): a {@code .bbmodel} the
 *       module installed into the Core's model folder; the Core serializes
 *       and binds it. The natural shape for hand-modelled static content.</li>
 *   <li><b>Framed raw models</b> ({@link #ofFrames}): verbatim model JSONs
 *       the module registers as raw pack assets, dispatched by frame state
 *       through the pack's {@code range_dispatch}. The natural shape for
 *       imported/animated content; the spec plus the install facts (folder
 *       stems and the per-item texture) travel together so a module can
 *       install and register from one object.</li>
 * </ul>
 *
 * <p>Both shapes are additive: vanilla items are never touched.
 */
public record ContentModel(
        String modelKey,
        ItemFrames frames,
        List<String> frameStems,
        String texture
) {

    public ContentModel {
        modelKey = modelKey == null || modelKey.isBlank() ? null : modelKey.trim();
        // The shapes are exclusive: a virtual model key wins over a frame spec.
        frames = modelKey != null ? null : frames;
        frameStems = frameStems == null ? List.of() : List.copyOf(frameStems);
        texture = texture == null || texture.isBlank() ? null : texture.trim();
        if (modelKey == null && frames == null) {
            throw new IllegalArgumentException("ContentModel needs a virtual model key or frame spec");
        }
    }

    /** A statically-modelled item rendered through its virtual model key. */
    public static ContentModel ofVirtual(String modelKey) {
        return new ContentModel(modelKey, null, List.of(), null);
    }

    /**
     * A frame-animated item: the {@link ItemFrames} drives the pack dispatch;
     * the stems and texture are the module's install facts for the raw JSONs.
     */
    public static ContentModel ofFrames(ItemFrames frames, List<String> frameStems, String texture) {
        return new ContentModel(null, frames, frameStems, texture);
    }

    /** True when the item is frame-animated (raw models + state dispatch). */
    public boolean framed() {
        return frames != null;
    }
}
