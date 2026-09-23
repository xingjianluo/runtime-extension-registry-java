package com.github.xingjianluo.runtimeextensionregistryjava.model;

import com.github.xingjianluo.runtimeextensionregistryjava.spi.Extension;

import java.util.Map;

/**
 * Immutable point-in-time view of one extension used by readers/executors.
 * A DISABLED snapshot carries a null instance; an ENABLED snapshot carries a live instance.
 */
public record ExtensionSnapshot(ExtensionDefinition definition, ExtensionStatus status,
                                Extension instance, Map<String, String> effectiveConfig) {

    public ExtensionSnapshot {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        effectiveConfig = effectiveConfig == null ? Map.of() : Map.copyOf(effectiveConfig);
        if (status == ExtensionStatus.ENABLED && instance == null) {
            throw new IllegalArgumentException("enabled snapshot must carry a live instance");
        }
        if (status == ExtensionStatus.DISABLED && instance != null) {
            throw new IllegalArgumentException("disabled snapshot must not carry an instance");
        }
    }

    /** An enabled snapshot with a live initialized instance. */
    public boolean isReady() {
        return status == ExtensionStatus.ENABLED && instance != null;
    }
}
