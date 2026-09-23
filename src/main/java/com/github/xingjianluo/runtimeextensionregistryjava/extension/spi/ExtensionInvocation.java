package com.github.xingjianluo.runtimeextensionregistryjava.extension.spi;

import java.util.Map;

/** 对某扩展的一次执行请求。 */
public record ExtensionInvocation(String extensionId, Map<String, Object> input) {
}
