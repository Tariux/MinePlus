package com.mineplus.infrastructure.persistence.codec;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mineplus.infrastructure.persistence.snapshot.MultiBlockSnapshot;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public final class MultiBlockCodec implements Codec<List<MultiBlockSnapshot>> {

    private static final Type LIST_TYPE = new TypeToken<List<MultiBlockSnapshot>>() { }
            .getType();

    private final Gson gson;

    public MultiBlockCodec(Gson gson) {
        this.gson = gson;
    }

    @Override
    public String encode(List<MultiBlockSnapshot> value) {
        List<MultiBlockSnapshot> safe = value == null ? List.of() : value;
        return gson.toJson(safe, LIST_TYPE);
    }

    @Override
    public List<MultiBlockSnapshot> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<MultiBlockSnapshot> decoded = gson.fromJson(raw, LIST_TYPE);
        if (decoded == null) {
            return List.of();
        }
        return new ArrayList<>(decoded);
    }
}
