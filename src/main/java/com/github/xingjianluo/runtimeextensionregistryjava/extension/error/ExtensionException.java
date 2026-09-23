package com.github.xingjianluo.runtimeextensionregistryjava.extension.error;

import java.util.Map;

/**
 * 注册中心对外的唯一异常形态：可区分错误码 + 可定位上下文，不携带底层堆栈透出。
 */
public class ExtensionException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> context;

    public ExtensionException(ErrorCode code, String message, Map<String, Object> context) {
        super(message);
        this.code = code;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public ExtensionException(ErrorCode code, String message, Map<String, Object> context, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> context() {
        return context;
    }
}
