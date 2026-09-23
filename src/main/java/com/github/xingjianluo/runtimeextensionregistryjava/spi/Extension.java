package com.github.xingjianluo.runtimeextensionregistryjava.spi;

import java.util.Map;

/**
 * Extension SPI. Implementations are created by an {@link ExtensionFactory}.
 * Lifecycle: initialize() once after construction, execute() any number of times, destroy() once.
 */
public interface Extension {
    void initialize(ExtensionRuntimeContext context) throws Exception;
    Map<String, Object> execute(Map<String, Object> input, ExtensionRuntimeContext context) throws Exception;
    default void destroy() { }
}
