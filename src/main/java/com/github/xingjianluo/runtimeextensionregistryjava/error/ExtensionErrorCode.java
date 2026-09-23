package com.github.xingjianluo.runtimeextensionregistryjava.error;

/** Distinguishable, externally visible failure reasons. */
public enum ExtensionErrorCode {
    DUPLICATE_EXTENSION_ID,
    UNKNOWN_EXTENSION,
    UNKNOWN_EXTENSION_KIND,
    INVALID_VERSION,
    INVALID_VERSION_RANGE,
    VERSION_INCOMPATIBLE,
    DEPENDENCY_NOT_FOUND,
    DEPENDENCY_NOT_READY,
    EXTENSION_NOT_READY,
    CYCLIC_DEPENDENCY,
    INVALID_CONFIG,
    INITIALIZATION_FAILED,
    EXECUTION_FAILED,
    CHANGE_SET_REJECTED,
    INVALID_REQUEST
}
