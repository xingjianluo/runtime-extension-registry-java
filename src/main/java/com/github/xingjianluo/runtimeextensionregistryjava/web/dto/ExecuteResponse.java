package com.github.xingjianluo.runtimeextensionregistryjava.web.dto;

import java.util.Map;

public record ExecuteResponse(String extensionId, String status, Map<String, Object> result) { }
