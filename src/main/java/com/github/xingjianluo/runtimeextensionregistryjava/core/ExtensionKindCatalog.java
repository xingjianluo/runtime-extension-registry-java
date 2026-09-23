package com.github.xingjianluo.runtimeextensionregistryjava.core;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of known extension kinds -> factories. Duplicate kinds are rejected. */
public final class ExtensionKindCatalog {

    private final Map<String, ExtensionFactory> factories = new ConcurrentHashMap<>();

    public ExtensionKindCatalog() {
    }

    public void register(ExtensionFactory factory) {
        if (factory == null || factory.kind() == null || factory.kind().isBlank()) {
            throw new IllegalArgumentException("factory and its kind must not be blank");
        }
        ExtensionFactory previous = factories.putIfAbsent(factory.kind(), factory);
        if (previous != null) {
            throw new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION_KIND,
                    "extension kind already registered: " + factory.kind(),
                    Map.of("kind", factory.kind()));
        }
    }

    public Optional<ExtensionFactory> find(String kind) {
        return Optional.ofNullable(factories.get(kind));
    }

    public ExtensionFactory require(String kind) {
        return find(kind).orElseThrow(() -> new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION_KIND,
                "unknown extension kind: " + kind, Map.of("kind", String.valueOf(kind))));
    }
}
