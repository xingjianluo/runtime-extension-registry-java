package com.github.xingjianluo.runtimeextensionregistryjava.builtin;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionRuntimeContext;

import java.util.Map;

/**
 * Built-in "faulty": throws during initialize and/or execute according to config
 * ({@code failOnInit=true}, {@code failOnExecute=true}). The thrown raw exception
 * must be normalized by the registry and never leak as a stack trace externally.
 */
public final class FaultyExtension implements Extension {

    public static final String KIND = "faulty";
    static final String FAIL_ON_INIT = "failOnInit";
    static final String FAIL_ON_EXECUTE = "failOnExecute";

    private String selfId;

    private FaultyExtension() {
    }

    public static ExtensionFactory factory() {
        return new Factory();
    }

    @Override
    public void initialize(ExtensionRuntimeContext context) {
        this.selfId = context.extensionId();
        if (Boolean.parseBoolean(context.config().getOrDefault(FAIL_ON_INIT, "false"))) {
            throw new IllegalStateException("simulated initialization fault in " + selfId);
        }
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExtensionRuntimeContext context) {
        if (Boolean.parseBoolean(context.config().getOrDefault(FAIL_ON_EXECUTE, "false"))) {
            throw new IllegalStateException("simulated execution fault in " + context.extensionId());
        }
        return Map.of("ok", true, "extensionId", context.extensionId());
    }

    private static final class Factory implements ExtensionFactory {
        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public void validateConfig(ExtensionDefinition definition) {
            for (String key : new String[]{FAIL_ON_INIT, FAIL_ON_EXECUTE}) {
                String value = definition.config().get(key);
                if (value != null && !value.equals("true") && !value.equals("false")) {
                    throw new ExtensionException(ExtensionErrorCode.INVALID_CONFIG,
                            "'" + key + "' must be 'true' or 'false'",
                            Map.of("extensionId", definition.id(), "key", key, "value", value));
                }
            }
        }

        @Override
        public Extension create(ExtensionDefinition definition) {
            return new FaultyExtension();
        }
    }
}
