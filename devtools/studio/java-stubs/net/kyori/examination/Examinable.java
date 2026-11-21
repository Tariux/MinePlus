package net.kyori.examination;

import java.util.stream.Stream;

/**
 * Linkage stub for the vendored-paper-api headless daemon: {@code Material}
 * implements {@code Translatable extends Examinable}, so loading the enum's
 * interface hierarchy requires this type to resolve. The bake path never
 * invokes examination methods — only class linkage is satisfied here.
 */
public interface Examinable {
    Stream<? extends ExaminableProperty> examinableProperties();
}
