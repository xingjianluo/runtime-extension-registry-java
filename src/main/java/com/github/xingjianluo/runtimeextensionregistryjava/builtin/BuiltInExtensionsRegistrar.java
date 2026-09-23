package com.github.xingjianluo.runtimeextensionregistryjava.builtin;

import com.github.xingjianluo.runtimeextensionregistryjava.core.ExtensionKindCatalog;
import org.springframework.stereotype.Component;

/** Registers all built-in extension kinds with the catalog at startup. */
@Component
public class BuiltInExtensionsRegistrar {

    public BuiltInExtensionsRegistrar(ExtensionKindCatalog catalog) {
        catalog.register(EchoExtension.factory());
        catalog.register(RelayExtension.factory());
        catalog.register(FaultyExtension.factory());
    }
}
