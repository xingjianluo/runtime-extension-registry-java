package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionFactory;

import java.util.List;
import java.util.Map;

/**
 * 扩展的静态定义：唯一标识、版本、依赖声明、是否启用、初始配置与实例工厂。
 *
 * <p>依赖列表与配置在构造时做不可变化拷贝，注册后的定义不会被外部改动。
 */
public record ExtensionDefinition(
        String id,
        Version version,
        List<DependencyRequirement> dependencies,
        boolean enabled,
        Map<String, Object> config,
        ExtensionFactory factory
) {
    public ExtensionDefinition {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
