package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;

/**
 * 一个历史版本相对其前一个已提交版本的差异汇总。
 *
 * @param version     本版本编号（与提交后的 generation 一致）
 * @param baseVersion 差异比较的基线版本编号；首个版本为 0（无基线）
 * @param kind        版本产生方式
 * @param changes     每个受影响扩展的差异列表（按扩展标识排序）
 */
public record VersionDiff(
        long version,
        long baseVersion,
        VersionKind kind,
        List<EntryChange> changes
) {
}
