package com.github.xingjianluo.runtimeextensionregistryjava.spi;

import java.util.Map;
import java.util.Optional;

/** Context handed to an extension during initialize/execute: its config and access to ready dependencies. */
public interface ExtensionRuntimeContext {
    String extensionId();
    Map<String, String> config();
    /** Resolve a currently ENABLED dependency; empty if absent or not ready. */
    Optional<Extension> dependency(String dependencyId);
}
