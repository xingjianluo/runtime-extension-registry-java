package com.github.xingjianluo.runtimeextensionregistryjava.web.dto;

import java.util.Map;

/** Safe external error view: stable code + human message + non-sensitive context. No stack traces. */
public record ErrorResponse(String errorCode, String message, Map<String, Object> context) { }
