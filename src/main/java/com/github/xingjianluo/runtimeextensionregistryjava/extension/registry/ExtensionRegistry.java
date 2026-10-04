package com.github.xingjianluo.runtimeextensionregistryjava.extension.registry;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryPage;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionResult;

import java.util.Optional;

/**
 * 运行时可热更新的扩展注册中心。
 *
 * <p>所有变更通过 {@link #apply} 提交：先在隔离环境完成全部校验与初始化，
 * 成功后以单一引用切换原子生效，失败则整体回滚、保持原状态可用。
 * 读取与执行始终基于某一已生效的不可变快照，不会读到撕裂状态。
 */
public interface ExtensionRegistry {

    /** 提交一批原子变更；失败抛 ExtensionException，原状态保持不变。 */
    ChangeOutcome apply(ChangeSet changeSet);

    /** 取当前已生效快照。 */
    RegistrySnapshot snapshot();

    /** 查询单个扩展的状态视图。 */
    Optional<ExtensionStatusView> status(String extensionId);

    /** 在当前世代上执行扩展；扩展不存在或依赖未就绪将被拒绝。 */
    ExtensionResult invoke(ExtensionInvocation invocation);

    /** 浏览保留的历史版本（默认按编号从新到旧）。 */
    HistoryPage history();

    /** 按编号取历史版本；编号不存在或已被淘汰时返回 empty。 */
    Optional<HistoryVersion> historyVersion(long version);

    /**
     * 回退到指定历史版本。回退本身是一次原子变更：成功后对外状态与目标版本一致并产生新编号；
     * 目标版本包含现已不存在的扩展、或按当前依赖与兼容关系无法就绪时整体拒绝。
     */
    ChangeOutcome rollback(long version);
}
