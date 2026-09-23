package com.github.xingjianluo.runtimeextensionregistryjava.builtin;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionRuntimeContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in "echo": requires config {@code prefix}; returns {@code {echo: prefix + message}}.
 * Serves as the happy-path extension used by local verification.
 */
public final class EchoExtension implements Extension {

    public static final String KIND = "echo";
    private static final String PREFIX_KEY = "prefix";

    private String prefix;

    private EchoExtension() {
    }

    public static ExtensionFactory factory() {
        return new Factory();
    }

    @Override
    public void initialize(ExtensionRuntimeContext context) {
        this.prefix = context.config().getOrDefault(PREFIX_KEY, "");
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExtensionRuntimeContext context) {
        Object message = input.get("message");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("echo", prefix + (message == null ? "" : message.toString()));
        return result;
    }

    private static final class Factory implements ExtensionFactory {
        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public void validateConfig(ExtensionDefinition definition) {
            String prefix = definition.config().get(PREFIX_KEY);
            if (prefix == null) {
                throw new ExtensionException(ExtensionErrorCode.INVALID_CONFIG,
                        "echo extension requires non-null 'prefix' config",
                        Map.of("extensionId", definition.id(), "missingKey", PREFIX_KEY));
            }
        }

        @Override
        public Extension create(ExtensionDefinition definition) {
            return new EchoExtension();
        }
    }
}
