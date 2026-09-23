package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;

/** 一次原子变更：命令要么整体生效，要么整体回滚。 */
public record ChangeSet(List<ChangeCommand> commands) {
}
