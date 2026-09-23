package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.Map;

/** 对注册中心的一次原子变更中的单条命令。 */
public sealed interface ChangeCommand
        permits ChangeCommand.RegisterDefinition,
                ChangeCommand.UpdateConfig,
                ChangeCommand.SetEnabled,
                ChangeCommand.RemoveExtension {

    /** 注册一个新扩展（含工厂）；标识重复（本批次或已存在）必须被拒绝。 */
    record RegisterDefinition(ExtensionDefinition definition) implements ChangeCommand {
    }

    /** 热更新指定扩展的配置。 */
    record UpdateConfig(String extensionId, Map<String, Object> config) implements ChangeCommand {
    }

    /** 启用或停用指定扩展。 */
    record SetEnabled(String extensionId, boolean enabled) implements ChangeCommand {
    }

    /** 移除指定扩展。 */
    record RemoveExtension(String extensionId) implements ChangeCommand {
    }
}
