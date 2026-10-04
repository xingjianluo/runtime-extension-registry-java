package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.Map;

/**
 * 历史版本中某个扩展的运行状态留存：标识、版本、启停、依赖声明与配置。
 * 不包含实例与工厂，仅为可序列化的只读状态。
 */
public record ExtensionStateSnapshot(
        String id,
        String version,
        boolean enabled,
        Map<String, String> dependencies,
        Map<String, Object> config
) {
}
