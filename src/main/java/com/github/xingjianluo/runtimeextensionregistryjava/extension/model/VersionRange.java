package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

/**
 * 兼容版本范围。
 *
 * <p>支持的写法：
 * <ul>
 *   <li>精确版本：{@code 1.2.3}（仅该版本满足）</li>
 *   <li>区间：{@code [1.0.0,2.0.0)}，方括号表示闭区间、圆括号表示开区间；
 *       任一端点留空表示无界，如 {@code [1.0.0,)}、{@code (,2.0.0]}</li>
 * </ul>
 */
public record VersionRange(String raw, Version lower, boolean lowerInclusive,
                           Version upper, boolean upperInclusive, boolean exact) {

    public VersionRange {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("version range must not be blank");
        }
    }

    /** 便捷构造：精确版本范围。 */
    public VersionRange(String raw) {
        this(raw, Version.parse(raw), true, Version.parse(raw), true, true);
    }

    /**
     * 解析版本范围表达式。
     *
     * @throws IllegalArgumentException 表达式非法时抛出，消息指明非法输入
     */
    public static VersionRange parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("version range must not be blank");
        }
        String trimmed = text.trim();
        boolean looksLikeRange = trimmed.startsWith("[") || trimmed.startsWith("(");
        if (!looksLikeRange) {
            // 精确版本
            return new VersionRange(trimmed);
        }
        if (trimmed.length() < 2 || !(trimmed.endsWith("]") || trimmed.endsWith(")"))) {
            throw new IllegalArgumentException("invalid version range (missing closing bracket): '" + text + "'");
        }
        boolean lowerInclusive = trimmed.charAt(0) == '[';
        boolean upperInclusive = trimmed.charAt(trimmed.length() - 1) == ']';
        String body = trimmed.substring(1, trimmed.length() - 1);
        String[] parts = body.split(",", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "invalid version range (expected 'lower,upper'): '" + text + "'");
        }
        String lowerText = parts[0].trim();
        String upperText = parts[1].trim();
        Version lower = lowerText.isEmpty() ? null : Version.parse(lowerText);
        Version upper = upperText.isEmpty() ? null : Version.parse(upperText);
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw new IllegalArgumentException(
                    "invalid version range (lower bound greater than upper bound): '" + text + "'");
        }
        return new VersionRange(trimmed, lower, lowerInclusive, upper, upperInclusive, false);
    }

    /** 判断给定版本是否落在兼容范围内。 */
    public boolean satisfies(Version version) {
        if (exact) {
            return lower.equals(version);
        }
        if (lower != null) {
            int cmp = version.compareTo(lower);
            if (lowerInclusive ? cmp < 0 : cmp <= 0) {
                return false;
            }
        }
        if (upper != null) {
            int cmp = version.compareTo(upper);
            if (upperInclusive ? cmp > 0 : cmp >= 0) {
                return false;
            }
        }
        return true;
    }
}
