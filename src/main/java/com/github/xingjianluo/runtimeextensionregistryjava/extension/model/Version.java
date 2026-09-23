package com.github.xingjianluo.runtimeextensionregistryjava.extension.model;

import java.util.Objects;

/** 语义化版本号（major.minor.patch），三段均为非负整数。 */
public final class Version implements Comparable<Version> {

    private final int major;
    private final int minor;
    private final int patch;

    public Version(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version segments must be non-negative: " + major + "." + minor + "." + patch);
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * 解析形如 {@code 1.2.3} 的版本串。
     *
     * @throws IllegalArgumentException 格式非法时抛出，消息指明非法输入
     */
    public static Version parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid version (expected major.minor.patch): '" + text + "'");
        }
        int[] segments = new int[3];
        for (int i = 0; i < 3; i++) {
            String part = parts[i];
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException("invalid version segment '" + part + "' in version: '" + text + "'");
            }
            try {
                segments[i] = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("version segment out of range in version: '" + text + "'", e);
            }
        }
        return new Version(segments[0], segments[1], segments[2]);
    }

    public int major() { return major; }
    public int minor() { return minor; }
    public int patch() { return patch; }

    @Override
    public int compareTo(Version other) {
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Version other)) {
            return false;
        }
        return major == other.major && minor == other.minor && patch == other.patch;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
