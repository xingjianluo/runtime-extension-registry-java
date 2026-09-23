package com.github.xingjianluo.runtimeextensionregistryjava.extension.spi;

import java.util.Map;

/**
 * 扩展契约。实现由 {@link ExtensionFactory} 创建；
 * initialize 在每次配置生效时调用，shutdown 在停用/移除/替换时调用。
 */
public interface Extension {

    /** 初始化（或基于新配置重新初始化）。无效配置应抛出 IllegalArgumentException。 */
    default void initialize(ExtensionContext context) throws Exception {
    }

    /** 执行业务动作。失败应抛出异常，由注册中心归一化为对外错误。 */
    default Object execute(Map<String, Object> input) throws Exception {
        return null;
    }

    /** 释放资源，停止接收请求。 */
    default void shutdown() {
    }
}
