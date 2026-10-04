package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;

/**
 * 历史版本浏览结果：列表级保留信息 + 版本条目（默认按编号从新到旧排列）。
 *
 * @param retentionLimit 历史保留条数上限
 * @param retainedCount 当前实际保留的版本条数
 * @param versions      版本轻量条目列表
 */
public record HistoryPage(
        int retentionLimit,
        int retainedCount,
        List<HistorySummary> versions
) {
}
