package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.Map;

/**
 * 两个相邻版本之间，单个扩展的启停与配置差异。
 *
 * @param type       差异类型
 * @param extensionId 扩展标识
 * @param enabledBefore 变更前启用状态（新增时为 null）
 * @param enabledAfter  变更后启用状态（移除时为 null）
 * @param configBefore  变更前配置（新增时为 null）
 * @param configAfter   变更后配置（移除时为 null）
 */
public record EntryChange(
        Type type,
        String extensionId,
        Boolean enabledBefore,
        Boolean enabledAfter,
        Map<String, Object> configBefore,
        Map<String, Object> configAfter
) {
    /** 单个扩展的差异类型。 */
    public enum Type {
        /** 上一版本不存在，本版本新出现。 */
        ADDED,
        /** 上一版本存在，本版本不存在。 */
        REMOVED,
        /** 启停状态发生变化。 */
        ENABLED_TOGGLED,
        /** 配置发生变化。 */
        CONFIG_CHANGED,
        /** 启停与配置同时变化。 */
        TOGGLED_AND_CONFIG_CHANGED
    }
}
