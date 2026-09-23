package com.github.xingjianluo.runtimeextensionregistryjava.extension.spi;

import java.util.Map;

/** 扩展执行结果：来自哪个世代与扩展，以及返回数据。 */
public record ExtensionResult(long generation, String extensionId, Object data) {

    public static ExtensionResult ok(long generation, String extensionId, Object data) {
        return new ExtensionResult(generation, extensionId, data);
    }
}
