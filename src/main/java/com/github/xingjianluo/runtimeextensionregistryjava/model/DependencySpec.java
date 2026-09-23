package com.github.xingjianluo.runtimeextensionregistryjava.model;

/** A dependency declaration: required extension id + accepted version range. */
public record DependencySpec(String extensionId, VersionRange acceptedRange) {

    public DependencySpec {
        if (extensionId == null || extensionId.isBlank()) {
            throw new IllegalArgumentException("dependency extensionId must not be blank");
        }
        if (acceptedRange == null) {
            throw new IllegalArgumentException("acceptedRange must not be null");
        }
    }

    public static DependencySpec parse(String extensionId, String rangeText) {
        return new DependencySpec(extensionId, new VersionRange(rangeText));
    }
}
