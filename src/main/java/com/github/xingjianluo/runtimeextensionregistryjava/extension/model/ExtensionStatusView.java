package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;
import java.util.Map;

/** 对外暴露的扩展状态视图（只读快照）。 */
public record ExtensionStatusView(
        String id,
        String version,
        boolean enabled,
        boolean ready,
        Map<String, String> dependencies,
        List<String> blockedBy,
        Map<String, Object> config
) {
}
