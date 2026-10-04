package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;

/**
 * 历史版本列表的轻量条目（不含完整快照与配置细节），用于版本浏览。
 *
 * @param version      版本编号
 * @param kind         产生方式
 * @param revertedFrom 回退目标版本编号（仅回退版本，否则为 null）
 * @param baseVersion  差异基线版本编号
 * @param changedIds   本次相对上一版本发生变化的扩展标识列表
 */
public record HistorySummary(
        long version,
        VersionKind kind,
        Long revertedFrom,
        long baseVersion,
        List<String> changedIds
) {
}
