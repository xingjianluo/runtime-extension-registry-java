package com.github.xingjianluo.runtimeextensionregistryjava.spi;

import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;

/**
 * Creates extension instances for one extension "kind".
 * validateConfig is used during change-set validation before any instance is created.
 */
public interface ExtensionFactory {
    String kind();
    void validateConfig(ExtensionDefinition definition) throws Exception;
    Extension create(ExtensionDefinition definition);
}
