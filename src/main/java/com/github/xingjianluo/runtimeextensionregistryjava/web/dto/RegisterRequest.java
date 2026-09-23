package com.github.xingjianluo.runtimeextensionregistryjava.web.dto;

import java.util.List;
import java.util.Map;

public record RegisterRequest(String id, String version, String kind,
                              List<DependencyDto> dependencies, Map<String, String> config) { }
