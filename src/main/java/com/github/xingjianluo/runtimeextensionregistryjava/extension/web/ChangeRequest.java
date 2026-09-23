package com.github.xingjianluo.runtimeextensionregistryjava.extension.web;

import java.util.Map;

/**
 * HTTP 变更请求体。支持热更新场景下的命令：
 * 启用/停用、配置更新、移除。
 */
public record ChangeRequest(
        String action,
        String extensionId,
        Boolean enabled,
        Map<String, Object> config
) {
}
