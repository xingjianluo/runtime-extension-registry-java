package com.github.xingjianluo.runtimeextensionregistryjava.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the kind catalog and the default registry as singleton Spring beans. */
@Configuration
public class RegistryConfiguration {

    @Bean
    public ExtensionKindCatalog extensionKindCatalog() {
        return new ExtensionKindCatalog();
    }

    @Bean
    public ExtensionRegistry extensionRegistry(ExtensionKindCatalog catalog) {
        return new DefaultExtensionRegistry(catalog);
    }
}
