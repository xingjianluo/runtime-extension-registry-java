package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.List;

/** 注册中心某一世代的不可变快照，读取方在一次请求内始终基于同一快照。 */
public record RegistrySnapshot(long generation, List<ExtensionStatusView> extensions) {
}
