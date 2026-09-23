package com.github.xingjianluo.runtimeextensionregistryjava.extension.sample;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例扩展：对配置严格校验，专门用于验证"无效配置导致整批变更被拒绝并回滚"。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code required}（必填，非空白字符串）：缺失或非字符串时抛
 *       {@link IllegalArgumentException}，由注册中心归一化为 INVALID_CONFIG。</li>
 *   <li>{@code simulateInitFault}（可选，布尔）：为 true 时在校验通过后抛出
 *       非校验类 {@link IllegalStateException}，归一化为 INITIALIZATION_FAILED，
 *       用于区分两类初始化失败。</li>
 * </ul>
 */
public class StrictConfigExtension implements Extension {

    private String required;

    @Override
    public void initialize(ExtensionContext context) {
        Object raw = context.config().get("required");
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("config 'required' must be a non-blank string");
        }
        Object fault = context.config().get("simulateInitFault");
        if (Boolean.TRUE.equals(fault)) {
            throw new IllegalStateException("simulated initialization fault");
        }
        this.required = value;
    }

    @Override
    public Object execute(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("required", required);
        result.put("echo", Map.copyOf(input));
        return result;
    }
}
