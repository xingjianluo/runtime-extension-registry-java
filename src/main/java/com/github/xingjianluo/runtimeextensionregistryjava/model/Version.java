package com.github.xingjianluo.runtimeextensionregistryjava.model;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;

import java.util.Map;

/**
 * Semantic version: major.minor.patch with non-negative numeric parts.
 * Missing minor/patch parts default to 0 ("1" -> 1.0.0).
 */
public final class Version implements Comparable<Version> {

    private final int major;
    private final int minor;
    private final int patch;

    public Version(int major, int minor, int patch) {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new ExtensionException(ExtensionErrorCode.INVALID_VERSION,
                    "version parts must be non-negative",
                    Map.of("major", major, "minor", minor, "patch", patch));
        }
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /** Parse a numeric dotted version of 1-3 parts, e.g. "1", "1.2", "1.2.3". */
    public static Version parse(String text) {
        if (text == null || text.isBlank()) {
            throw new ExtensionException(ExtensionErrorCode.INVALID_VERSION,
                    "version must not be blank", Map.of("input", String.valueOf(text)));
        }
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length < 1 || parts.length > 3) {
            throw invalid(trimmed, "version must have 1 to 3 numeric parts, e.g. 1.2.3");
        }
        int[] values = new int[3];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.matches("\\d+")) {
                throw invalid(trimmed, "each version part must be numeric");
            }
            try {
                values[i] = Integer.parseInt(part);
            } catch (NumberFormatException nfe) {
                throw invalid(trimmed, "version part out of range");
            }
        }
        return new Version(values[0], values[1], values[2]);
    }

    private static ExtensionException invalid(String input, String reason) {
        return new ExtensionException(ExtensionErrorCode.INVALID_VERSION,
                "invalid version '" + input + "': " + reason,
                Map.of("input", input, "reason", reason));
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

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
        return (major * 31 + minor) * 31 + patch;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
