package com.mineplus.infrastructure.core.util;

import java.util.Locale;

public final class StringNormalizer {

    private StringNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).trim();
    }
}
