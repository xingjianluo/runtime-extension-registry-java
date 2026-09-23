package com.github.xingjianluo.runtimeextensionregistryjava.model;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;

import java.util.Map;

/**
 * Compatibility range with explicit, explainable syntax:
 * <ul>
 *   <li>{@code 1.2.0} - exact version only.</li>
 *   <li>{@code [1.0.0,2.0.0)} - bounded interval; a square/round bracket on each
 *       side means inclusive/exclusive, e.g. {@code [1.0.0,2.0.0)}, {@code (,1.5.0]},
 *       {@code [1.0.0,)}.</li>
 * </ul>
 * An empty side of a bracketed range means unbounded.
 */
public final class VersionRange {

    private final String raw;
    private final boolean exact;
    private final Version lower;
    private final boolean lowerInclusive;
    private final Version upper;
    private final boolean upperInclusive;

    public VersionRange(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ExtensionException(ExtensionErrorCode.INVALID_VERSION_RANGE,
                    "version range must not be blank", Map.of("input", String.valueOf(raw)));
        }
        this.raw = raw.trim();
        boolean hasBrackets = this.raw.length() >= 2
                && (this.raw.startsWith("[") || this.raw.startsWith("("))
                && (this.raw.endsWith("]") || this.raw.endsWith(")"));
        if (!hasBrackets) {
            // An unbracketed token means exact version; version-shaped parse errors are
            // reported as range parse failures for the caller.
            this.exact = true;
            try {
                this.lower = Version.parse(this.raw);
            } catch (ExtensionException e) {
                if (e.errorCode() == ExtensionErrorCode.INVALID_VERSION) {
                    throw malformed("exact range token must be a numeric version");
                }
                throw e;
            }
            this.upper = this.lower;
            this.lowerInclusive = true;
            this.upperInclusive = true;
        } else {
            this.exact = false;
            this.lowerInclusive = this.raw.startsWith("[");
            this.upperInclusive = this.raw.endsWith("]");
            String body = this.raw.substring(1, this.raw.length() - 1);
            int comma = body.indexOf(',');
            if (comma < 0) {
                throw malformed("bracketed range must contain exactly one comma, e.g. [1.0.0,2.0.0)");
            }
            String lowerText = body.substring(0, comma).trim();
            String upperText = body.substring(comma + 1).trim();
            if (body.indexOf(',', comma + 1) >= 0) {
                throw malformed("bracketed range must contain exactly one comma");
            }
            try {
                this.lower = lowerText.isEmpty() ? null : Version.parse(lowerText);
                this.upper = upperText.isEmpty() ? null : Version.parse(upperText);
            } catch (ExtensionException e) {
                if (e.errorCode() == ExtensionErrorCode.INVALID_VERSION) {
                    throw malformed("range bound must be a numeric version");
                }
                throw e;
            }
            if (this.lower != null && this.upper != null && this.lower.compareTo(this.upper) > 0) {
                throw malformed("lower bound must not be greater than upper bound");
            }
        }
    }

    private ExtensionException malformed(String reason) {
        return new ExtensionException(ExtensionErrorCode.INVALID_VERSION_RANGE,
                "invalid version range '" + raw + "': " + reason,
                Map.of("input", raw, "reason", reason));
    }

    public boolean contains(Version v) {
        if (v == null) {
            return false;
        }
        if (exact) {
            return v.compareTo(lower) == 0;
        }
        if (lower != null) {
            int cmp = v.compareTo(lower);
            if (cmp < 0 || (cmp == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (upper != null) {
            int cmp = v.compareTo(upper);
            if (cmp > 0 || (cmp == 0 && !upperInclusive)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return raw;
    }
}
