package com.github.xingjianluo.runtimeextensionregistryjava.web.dto;

import java.util.Map;

public record ChangeItemDto(String extensionId, Boolean enabled, Map<String, String> config) { }
