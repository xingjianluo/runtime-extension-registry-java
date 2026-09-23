package com.github.xingjianluo.runtimeextensionregistryjava.extension.spi;

import java.util.Map;
import java.util.Optional;

/**
 * 扩展初始化时获得的上下文：当前配置以及按依赖标识获取已就绪依赖实例的能力。
 */
public interface ExtensionContext {

    /** 当前扩展的生效配置（不可变）。 */
    Map<String, Object> config();

    /** 取已就绪的依赖扩展实例；依赖不存在或未就绪时返回 empty。 */
    Optional<Extension> dependency(String extensionId);
}
