package com.mineplus.infrastructure.persistence.codec;

public interface Codec<T> {

    String encode(T value);

    T decode(String raw);
}
