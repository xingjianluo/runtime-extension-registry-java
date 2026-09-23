package com.github.xingjianluo.runtimeextensionregistryjava.builtin;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.DependencySpec;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionRuntimeContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Built-in "relay": forwards execution to one declared dependency looked up through
 * the runtime context (so it can never invoke a not-ready dependency). Its single
 * declared dependency id is the target.
 */
public final class RelayExtension implements Extension {

    public static final String KIND = "relay";

    private String targetId;

    private RelayExtension() {
    }

    public static ExtensionFactory factory() {
        return new Factory();
    }

    @Override
    public void initialize(ExtensionRuntimeContext context) {
        if (context.dependency(targetId).isEmpty()) {
            throw new ExtensionException(ExtensionErrorCode.DEPENDENCY_NOT_READY,
                    "relay target dependency is not ready during initialization",
                    Map.of("extensionId", context.extensionId(), "dependencyId", targetId));
        }
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExtensionRuntimeContext context) {
        Extension target = context.dependency(targetId).orElseThrow(() ->
                new ExtensionException(ExtensionErrorCode.DEPENDENCY_NOT_READY,
                        "relay target dependency is not ready",
                        Map.of("extensionId", context.extensionId(), "dependencyId", targetId)));
        try {
            Map<String, Object> downstream = target.execute(input, context);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("relayedBy", context.extensionId());
            result.put("target", targetId);
            result.put("downstream", downstream);
            return result;
        } catch (ExtensionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtensionException(ExtensionErrorCode.EXECUTION_FAILED,
                    "relayed dependency execution failed",
                    Map.of("extensionId", context.extensionId(), "dependencyId", targetId), e);
        }
    }

    private static final class Factory implements ExtensionFactory {
        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public void validateConfig(ExtensionDefinition definition) {
            if (definition.dependencies().size() != 1) {
                throw new ExtensionException(ExtensionErrorCode.INVALID_CONFIG,
                        "relay extension must declare exactly one dependency",
                        Map.of("extensionId", definition.id(),
                                "dependencyCount", definition.dependencies().size()));
            }
        }

        @Override
        public Extension create(ExtensionDefinition definition) {
            RelayExtension extension = new RelayExtension();
            extension.targetId = definition.dependencies().get(0).extensionId();
            return extension;
        }
    }
}
