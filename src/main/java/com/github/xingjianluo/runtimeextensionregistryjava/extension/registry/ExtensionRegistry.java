package com.github.xingjianluo.runtimeextensionregistryjava.extension.registry;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionResult;

import java.util.List;
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

    /** 列出当前留存的历史版本（按编号升序，最旧在前）。 */
    List<HistoryVersion> history();

    /**
     * 按编号查询历史版本。
     *
     * @throws com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException
     *         错误码 {@code VERSION_NOT_FOUND}：版本从未产生或已被淘汰
     */
    HistoryVersion historyVersion(long number);

    /**
     * 回退到指定历史版本：本身是一次原子变更，成功则生成更新编号的历史版本，
     * 失败（目标含已不存在扩展、或按当前依赖与兼容关系无法就绪）整体拒绝、当前状态原样可用。
     *
     * <p>回退成功后，对外可见的扩展集合、启停状态与配置与目标版本逐项一致：
     * 目标版本之后才注册的扩展随回退一并移除（与状态恢复同属一次原子提交）；
     * 扩展的版本、依赖声明与工厂始终以当前注册为准，不复活旧定义。
     *
     * @throws com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException
     *         {@code VERSION_NOT_FOUND}（目标版本查不到）或 {@code ROLLBACK_REJECTED}（无法整体生效）
     */
    ChangeOutcome rollback(long number);
}
