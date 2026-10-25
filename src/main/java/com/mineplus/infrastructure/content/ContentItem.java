package com.mineplus.infrastructure.content;

import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.pack.item.PackItemDefinition;
import java.util.List;
import org.bukkit.Material;

/**
 * The shared <b>content definition entity</b> — one named, presented,
 * purchasable custom item, independent of any one module's gameplay. This is
 * the shape every Mineplus module hands to the pack subsystem and (when it
 * has a shop) lists for sale:
 *
 * <pre>{@code
 * ContentItem colt = ContentItems.parse("mineplusgun", "colt_peacemaker", section);
 * context.packApi().registerItem(colt.toPackItem());
 * }</pre>
 *
 * <p>Identity/resources: {@code namespace}/{@code id} double as the pack
 * item identity ({@code <namespace>:<id>}); {@link #model()} carries the
 * presentation (virtual model or frame animation).
 * {@link #toPackItem()} derives the {@link PackItemDefinition} — modules
 * never re-wrap these fields by hand.</p>
 *
 * <p>Gameplay (what the item <em>does</em>) stays in the owning module: a
 * weapon definition, a juice, a tool are gameplay records that hold their
 * {@link ContentItem} plus their behaviour fields.</p>
 */
public record ContentItem(
        String namespace,
        String id,
        String displayName,
        List<String> descriptionLines,
        ItemCategory category,
        Material carrier,
        double price,
        boolean hidden,
        ContentModel model
) {

    public ContentItem {
        namespace = normalize(namespace);
        id = normalize(id);
        descriptionLines = descriptionLines == null ? List.of() : List.copyOf(descriptionLines);
        category = category == null ? ItemCategory.UTILITY : category;
        carrier = carrier == null ? ContentItems.DEFAULT_CARRIER : carrier;
        price = Math.max(0.0, price);
        model = model == null ? null : model;
    }

    private static String normalize(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid content item token: '" + value + "'");
        }
        return normalized;
    }

    /** The logical identity {@code <namespace>:<id>} (the ItemRegistry key). */
    public String key() {
        return namespace + ":" + id;
    }

    /** The pack presentation, or {@code null} when the item has no custom model. */
    public ContentModel model() {
        return model;
    }

    /** A copy of this item with another category (modules specialize generic content). */
    public ContentItem withCategory(ItemCategory category) {
        return category == this.category ? this
                : new ContentItem(namespace, id, displayName, descriptionLines, category,
                        carrier, price, hidden, model);
    }

    /**
     * The pack item definition this content derives — identity, carrier,
     * presentation (virtual model or frame dispatch), display text. The
     * module registers the result with {@code PackApi.registerItem}.
     */
    public PackItemDefinition toPackItem() {
        if (model == null) {
            return null;
        }
        PackItemDefinition.Builder builder = PackItemDefinition.builder(
                        namespace, id, carrier, model.framed() ? null : model.modelKey())
                .displayName(displayName)
                .descriptionLines(descriptionLines)
                .category(category);
        if (model.framed()) {
            builder.frames(model.frames());
        }
        return builder.build();
    }
}
