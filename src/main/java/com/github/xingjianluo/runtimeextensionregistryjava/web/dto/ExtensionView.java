package com.github.xingjianluo.runtimeextensionregistryjava.web.dto;

import java.util.List;
import java.util.Map;

public record ExtensionView(String id, String version, String kind, String status,
                            List<DependencyView> dependencies, Map<String, String> config) { }
