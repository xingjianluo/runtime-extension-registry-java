package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.github.xingjianluo.runtimeextensionregistryjava.builtin.EchoExtension;
import com.github.xingjianluo.runtimeextensionregistryjava.builtin.FaultyExtension;
import com.github.xingjianluo.runtimeextensionregistryjava.builtin.RelayExtension;
import com.github.xingjianluo.runtimeextensionregistryjava.core.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.core.ExtensionKindCatalog;
import com.github.xingjianluo.runtimeextensionregistryjava.model.DependencySpec;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;

import java.util.List;
import java.util.Map;

/** Shared helpers for registry unit tests. */
final class TestFixtures {

    private TestFixtures() {
    }

    static DefaultExtensionRegistry newRegistry() {
        ExtensionKindCatalog catalog = new ExtensionKindCatalog();
        catalog.register(EchoExtension.factory());
        catalog.register(RelayExtension.factory());
        catalog.register(FaultyExtension.factory());
        return new DefaultExtensionRegistry(catalog);
    }

    static ExtensionDefinition echo(String id, String version, String prefix) {
        return new ExtensionDefinition(id, version, EchoExtension.KIND,
                List.of(), Map.of("prefix", prefix));
    }

    static ExtensionDefinition echoWithDep(String id, String version, String prefix,
                                           String depId, String range) {
        return new ExtensionDefinition(id, version, EchoExtension.KIND,
                List.of(new DependencySpec(depId,
                        new com.github.xingjianluo.runtimeextensionregistryjava.model.VersionRange(range))),
                Map.of("prefix", prefix));
    }

    static ExtensionDefinition relay(String id, String depId, String range) {
        return new ExtensionDefinition(id, "1.0.0", RelayExtension.KIND,
                List.of(new DependencySpec(depId,
                        new com.github.xingjianluo.runtimeextensionregistryjava.model.VersionRange(range))),
                Map.of());
    }

    static ExtensionDefinition faulty(String id, String failOnInit, String failOnExecute) {
        java.util.Map<String, String> cfg = new java.util.HashMap<>();
        if (failOnInit != null) {
            cfg.put("failOnInit", failOnInit);
        }
        if (failOnExecute != null) {
            cfg.put("failOnExecute", failOnExecute);
        }
        return new ExtensionDefinition(id, "1.0.0", FaultyExtension.KIND, List.of(), cfg);
    }
}
