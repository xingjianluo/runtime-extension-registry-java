package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

/** 一次成功变更的结果：新生效的世代号与生效后的快照。 */
public record ChangeOutcome(long generation, RegistrySnapshot snapshot) {
}
