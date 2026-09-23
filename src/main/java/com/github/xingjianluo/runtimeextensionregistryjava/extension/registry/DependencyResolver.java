package com.github.xingjianluo.runtimeextensionregistryjava.extension.registry;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.DependencyRequirement;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.VersionRange;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 包内部：对候选定义集合执行依赖校验。
 *
 * <p>依次给出可区分的失败结论：
 * <ol>
 *   <li>版本范围表达式非法 → {@link ErrorCode#INVALID_VERSION_RANGE}</li>
 *   <li>依赖在候选集合中不存在 → {@link ErrorCode#MISSING_DEPENDENCY}</li>
 *   <li>依赖存在但版本不在兼容范围 → {@link ErrorCode#INCOMPATIBLE_DEPENDENCY_VERSION}</li>
 *   <li>依赖图存在环 → {@link ErrorCode#CYCLIC_DEPENDENCY}（上下文携带环链）</li>
 * </ol>
 * 校验通过后输出拓扑序（被依赖者排在前面），供初始化按序进行。
 */
final class DependencyResolver {

    /** 校验结果：解析后的范围映射 + 拓扑序（依赖在前）。 */
    record Resolved(Map<String, Map<String, VersionRange>> ranges, List<String> topologicalOrder) {
    }

    private DependencyResolver() {
    }

    static Resolved resolve(Map<String, ExtensionDefinition> candidates) {
        Map<String, Map<String, VersionRange>> parsedRanges = parseRanges(candidates);
        checkDependenciesExistAndCompatible(candidates, parsedRanges);
        List<String> order = topologicalSort(candidates, parsedRanges);
        return new Resolved(parsedRanges, order);
    }

    private static Map<String, Map<String, VersionRange>> parseRanges(
            Map<String, ExtensionDefinition> candidates) {
        Map<String, Map<String, VersionRange>> result = new LinkedHashMap<>();
        for (ExtensionDefinition def : candidates.values()) {
            Map<String, VersionRange> ranges = new LinkedHashMap<>();
            Set<String> declared = new HashSet<>();
            for (DependencyRequirement req : def.dependencies()) {
                if (!declared.add(req.extensionId())) {
                    throw new ExtensionException(
                            ErrorCode.BAD_REQUEST,
                            "duplicate dependency declaration",
                            Map.of("extensionId", def.id(), "dependencyId", req.extensionId()));
                }
                try {
                    ranges.put(req.extensionId(), VersionRange.parse(req.versionRangeRaw()));
                } catch (IllegalArgumentException e) {
                    throw new ExtensionException(
                            ErrorCode.INVALID_VERSION_RANGE,
                            "invalid dependency version range: " + e.getMessage(),
                            Map.of("extensionId", def.id(),
                                    "dependencyId", req.extensionId(),
                                    "versionRange", String.valueOf(req.versionRangeRaw())),
                            e);
                }
            }
            result.put(def.id(), Map.copyOf(ranges));
        }
        return result;
    }

    private static void checkDependenciesExistAndCompatible(
            Map<String, ExtensionDefinition> candidates,
            Map<String, Map<String, VersionRange>> parsedRanges) {
        for (ExtensionDefinition def : candidates.values()) {
            for (DependencyRequirement req : def.dependencies()) {
                ExtensionDefinition dep = candidates.get(req.extensionId());
                if (dep == null) {
                    throw new ExtensionException(
                            ErrorCode.MISSING_DEPENDENCY,
                            "extension '" + def.id() + "' requires missing dependency '" + req.extensionId() + "'",
                            Map.of("extensionId", def.id(),
                                    "dependencyId", req.extensionId(),
                                    "requiredRange", String.valueOf(req.versionRangeRaw())));
                }
                VersionRange range = parsedRanges.get(def.id()).get(req.extensionId());
                if (!range.satisfies(dep.version())) {
                    throw new ExtensionException(
                            ErrorCode.INCOMPATIBLE_DEPENDENCY_VERSION,
                            "extension '" + def.id() + "' requires '" + req.extensionId()
                                    + "' " + req.versionRangeRaw() + " but found " + dep.version(),
                            Map.of("extensionId", def.id(),
                                    "dependencyId", req.extensionId(),
                                    "requiredRange", String.valueOf(req.versionRangeRaw()),
                                    "actualVersion", dep.version().toString()));
                }
            }
        }
    }

    private static List<String> topologicalSort(
            Map<String, ExtensionDefinition> candidates,
            Map<String, Map<String, VersionRange>> parsedRanges) {
        // DFS 三色法：发现回边即定位出环链
        Map<String, Integer> color = new LinkedHashMap<>();
        candidates.keySet().forEach(id -> color.put(id, 0));
        List<String> order = new ArrayList<>();
        List<String> path = new ArrayList<>();

        for (String id : candidates.keySet()) {
            if (color.get(id) == 0) {
                visit(id, candidates, parsedRanges, color, order, path);
            }
        }
        return order;
    }

    private static void visit(String id,
                              Map<String, ExtensionDefinition> candidates,
                              Map<String, Map<String, VersionRange>> parsedRanges,
                              Map<String, Integer> color,
                              List<String> order,
                              List<String> path) {
        color.put(id, 1);
        path.add(id);
        for (String depId : parsedRanges.get(id).keySet()) {
            int depColor = color.get(depId);
            if (depColor == 1) {
                // 回边：从环上首次出现的位置截取到当前路径末尾，再补上起点形成可读环链
                int start = path.indexOf(depId);
                List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
                cycle.add(depId);
                throw new ExtensionException(
                        ErrorCode.CYCLIC_DEPENDENCY,
                        "cyclic dependency detected: " + String.join(" -> ", cycle),
                        Map.of("cycle", cycle.toString()));
            }
            if (depColor == 0) {
                visit(depId, candidates, parsedRanges, color, order, path);
            }
        }
        path.remove(path.size() - 1);
        color.put(id, 2);
        order.add(id);
    }
}
