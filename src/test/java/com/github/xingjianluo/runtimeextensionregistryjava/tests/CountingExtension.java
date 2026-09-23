package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionRuntimeContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only extension whose factory counts create/initialize/destroy lifecycle calls. */
final class CountingExtension implements Extension {

    static final String KIND = "counting";

    final AtomicInteger initializes;
    final AtomicInteger destroys;

    CountingExtension(AtomicInteger initializes, AtomicInteger destroys) {
        this.initializes = initializes;
        this.destroys = destroys;
    }

    @Override
    public void initialize(ExtensionRuntimeContext context) {
        initializes.incrementAndGet();
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExtensionRuntimeContext context) {
        return Map.of("initializesSeen", initializes.get());
    }

    @Override
    public void destroy() {
        destroys.incrementAndGet();
    }

    static ExtensionFactory factory(AtomicInteger creates, AtomicInteger initializes,
                                    AtomicInteger destroys) {
        return new ExtensionFactory() {
            @Override
            public String kind() {
                return KIND;
            }

            @Override
            public void validateConfig(ExtensionDefinition definition) {
                // always valid
            }

            @Override
            public Extension create(ExtensionDefinition definition) {
                creates.incrementAndGet();
                return new CountingExtension(initializes, destroys);
            }
        };
    }
}
