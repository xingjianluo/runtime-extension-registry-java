package com.github.xingjianluo.runtimeextensionregistryjava.core;

import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionSnapshot;

import java.util.Map;

/** Runtime extension registry: registration, atomic state changes, consistent reads, isolated execution. */
public interface ExtensionRegistry {
    /** Static registration. Fails on duplicate id (never overwrites). */
    void register(ExtensionDefinition definition);
    ExtensionSnapshot snapshot(String extensionId);
    Map<String, ExtensionSnapshot> snapshots();
    /** Apply a change set atomically: all-or-nothing; previous usable state stays available on failure. */
    void applyChange(StateChangeRequest request);
    /** Execute an enabled extension. Fails fast if missing/not-ready. Failures are isolated and normalized. */
    Map<String, Object> execute(String extensionId, Map<String, Object> input);

    /** Monotonically increasing generation of the published state (changes each accepted mutation). */
    long currentGeneration();
}
