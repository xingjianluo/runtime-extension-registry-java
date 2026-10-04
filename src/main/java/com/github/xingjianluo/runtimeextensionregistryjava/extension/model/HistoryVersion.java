package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

/**
 * 一个历史版本的完整留存：编号、产生方式、回退目标（仅回退版本）、
 * 相对上一版本的差异、以及该版本提交时刻的完整状态快照。
 *
 * @param version       版本编号（与提交后的 generation 一致，严格递增）
 * @param kind          产生方式
 * @param revertedFrom  若为回退版本，记录其回退目标版本编号；否则为 null
 * @param diff          相对上一已提交版本的差异
 *  @param snapshot      该版本完整状态快照
 */
public record HistoryVersion(
        long version,
        VersionKind kind,
        Long revertedFrom,
        VersionDiff diff,
        RegistrySnapshot snapshot
) {
}
