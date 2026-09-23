package com.github.xingjianluo.runtimeextensionregistryjava.error;

import java.util.Map;

/**
 * Normalized failure carrying a stable {@link ExtensionErrorCode}, a safe message
 * and diagnostic context. Context is for logging/locating the problem; only safe
 * entries are exposed externally. Raw cause stack traces are never serialized out.
 */
public class ExtensionException extends RuntimeException {

    private final ExtensionErrorCode errorCode;
    private final transient Map<String, Object> context;

    public ExtensionException(ExtensionErrorCode code, String message) {
        this(code, message, Map.of(), null);
    }

    public ExtensionException(ExtensionErrorCode code, String message, Map<String, Object> context) {
        this(code, message, context, null);
    }

    public ExtensionException(ExtensionErrorCode code, String message, Map<String, Object> context, Throwable cause) {
        super(message == null ? code.name() : message, cause);
        if (code == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.errorCode = code;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public ExtensionErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> context() {
        return context;
    }

    /** Helper for wrapping an underlying failure without exposing its message externally. */
    public static ExtensionException wrap(ExtensionErrorCode code, String safeMessage,
                                          Map<String, Object> context, Throwable cause) {
        return new ExtensionException(code, safeMessage, context, cause);
    }
}
