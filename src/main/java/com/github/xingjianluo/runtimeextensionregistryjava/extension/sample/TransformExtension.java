package com.github.xingjianluo.runtimeextensionregistryjava.extension.sample;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 示例扩展：依赖 {@code echo}（兼容 [1.0.0,2.0.0)），先调用依赖再对文本转换大小写。
 *
 * <p>配置项：{@code mode}（必填）= {@code UPPER} 或 {@code LOWER}，其他值初始化失败，
 * 用于演示 INVALID_CONFIG 与原子回滚。
 */
public class TransformExtension implements Extension {

    private Extension echo;
    private String mode;

    @Override
    public void initialize(ExtensionContext context) {
        Object rawMode = context.config().get("mode");
        if (!(rawMode instanceof String modeValue)
                || (!modeValue.equals("UPPER") && !modeValue.equals("LOWER"))) {
            throw new IllegalArgumentException("config 'mode' must be either UPPER or LOWER");
        }
        // 依赖未就绪时本扩展不会被初始化（注册中心保证），此处为空属于内部断言失败。
        this.echo = context.dependency("echo")
                .orElseThrow(() -> new ExtensionException(ErrorCode.DEPENDENCY_NOT_READY,
                        "echo dependency not available during initialization",
                        Map.of("extensionId", "transform")));
        this.mode = modeValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> input) throws Exception {
        Object text = input.get("text");
        if (!(text instanceof String textValue)) {
            throw new IllegalArgumentException("input 'text' must be a string");
        }
        // 调用依赖扩展：故障隔离下，依赖抛错会被注册中心归一化为 EXECUTION_FAILED。
        Object echoResult = echo.execute(Map.of("text", textValue));
        String transformed = "UPPER".equals(mode) ? textValue.toUpperCase() : textValue.toLowerCase();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("text", transformed);
        result.put("upstream", echoResult);
        return result;
    }
}
