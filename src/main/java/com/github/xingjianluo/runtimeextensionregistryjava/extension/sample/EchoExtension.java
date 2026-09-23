package com.github.xingjianluo.runtimeextensionregistryjava.extension.sample;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例扩展：回显输入与当前生效配置，无依赖。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code prefix}（可选，字符串）：加在回显消息前的前缀，默认 {@code echo}</li>
 * </ul>
 * 该扩展的初始化不会失败，便于作为稳定依赖项。
 */
public class EchoExtension implements Extension {

    private volatile String prefix = "echo";

    @Override
    public void initialize(ExtensionContext context) {
        Object raw = context.config().get("prefix");
        if (raw != null && !(raw instanceof String)) {
            throw new IllegalArgumentException("config 'prefix' must be a string");
        }
        this.prefix = raw == null ? "echo" : (String) raw;
    }

    @Override
    public Object execute(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prefix", prefix);
        result.put("input", Map.copyOf(input));
        return result;
    }
}
