package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;

/**
 * 一次成功提交留存的历史版本。
 *
 * @param number     历史版本号：即该次提交产生的世代号，严格递增；回退提交同样生成新编号
 * @param changes    本版本相对上一留存版本的逐项变更（启停与配置等）
 * @param extensions 本版本生效后的完整运行状态（全部扩展的启停与配置）
 */
public record HistoryVersion(long number,
                             List<ExtensionChange> changes,
                             List<ExtensionStateSnapshot> extensions) {
}
