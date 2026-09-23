package com.github.xingjianluo.runtimeextensionregistryjava.core;

import java.util.Map;

/** One change item: enabled==null keeps status; config==null keeps config. */
public record StateItem(String extensionId, Boolean enabled, Map<String, String> config) {
    public StateItem {
        if (extensionId == null || extensionId.isBlank()) {
            throw new IllegalArgumentException("extensionId must not be blank");
        }
    }
}
