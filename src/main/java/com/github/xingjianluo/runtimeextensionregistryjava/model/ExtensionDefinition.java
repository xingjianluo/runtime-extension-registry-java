package com.github.xingjianluo.runtimeextensionregistryjava.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable registration descriptor of an extension:
 * unique id, own version, implementation kind, declared dependencies and base config.
 */
public final class ExtensionDefinition {

    private final String id;
    private final Version version;
    private final String kind;
    private final List<DependencySpec> dependencies;
    private final Map<String, String> config;

    public ExtensionDefinition(String id, String version, String kind,
                               List<DependencySpec> dependencies, Map<String, String> config) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("extension id must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("extension kind must not be blank");
        }
        this.id = id;
        this.version = Version.parse(version);
        this.kind = kind;
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        this.config = config == null ? Map.of() : Map.copyOf(config);
    }

    public String id() {
        return id;
    }

    public Version version() {
        return version;
    }

    public String kind() {
        return kind;
    }

    public List<DependencySpec> dependencies() {
        return dependencies;
    }

    public Map<String, String> config() {
        return config;
    }
}
