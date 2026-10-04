package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.Map;

/**
 * 一个历史版本相对上一版本对某个扩展的一项变更。
 *
 * @param extensionId 被变更的扩展标识
 * @param kind        变更种类
 * @param oldConfig   变更前配置（仅 CONFIG_CHANGED/REMOVED 等非空语义场景提供，否则为 null）
 * @param newConfig   变更后配置（仅 CONFIG_CHANGED/REGISTERED 等非空语义场景提供，否则为 null）
 */
public record ExtensionChange(String extensionId, ChangeKind kind,
                              Map<String, Object> oldConfig, Map<String, Object> newConfig) {

    /** 变更种类。 */
    public enum ChangeKind {
        /** 该版本新注册的扩展。 */
        REGISTERED,
        /** 该版本被移除的扩展。 */
        REMOVED,
        /** 由停用变为启用。 */
        ENABLED,
        /** 由启用变为停用。 */
        DISABLED,
        /** 配置发生变化（oldConfig/newConfig 给出前后值）。 */
        CONFIG_CHANGED,
        /** 版本或依赖声明发生变化（移除后重新注册同一标识）。 */
        REDEFINED
    }
}
