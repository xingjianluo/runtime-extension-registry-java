package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

/**
 * 对某个依赖扩展的标识与兼容版本范围要求。
 *
 * @param extensionId      依赖扩展的唯一标识
 * @param versionRangeRaw  兼容版本范围表达式（精确版本或区间），
 *                         由注册中心在提交时统一解析，非法表达式被拒绝
 */
public record DependencyRequirement(String extensionId, String versionRangeRaw) {

    public DependencyRequirement {
        if (extensionId == null || extensionId.isBlank()) {
            throw new IllegalArgumentException("dependency extensionId must not be blank");
        }
        if (versionRangeRaw == null || versionRangeRaw.isBlank()) {
            throw new IllegalArgumentException("dependency version range must not be blank");
        }
    }
}
