package com.github.xingjianluo.runtimeextensionregistryjava.core;

import java.util.List;
import java.util.Map;

/**
 * Atomic change set. For each item: enabled==null keeps current status;
 * config==null keeps current config. Either all items become effective or none.
 */
public record StateChangeRequest(List<StateItem> items) {
    public StateChangeRequest {
        if (items == null) throw new IllegalArgumentException("items must not be null");
    }
}
