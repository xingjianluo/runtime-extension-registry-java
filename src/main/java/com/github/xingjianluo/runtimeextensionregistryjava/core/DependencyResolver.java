package com.github.xingjianluo.runtimeextensionregistryjava.core;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.DependencySpec;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Validates the dependency graph against a target configuration:
 * existence, version compatibility, readiness (enabled in target) and cycles.
 * Readiness is evaluated through the supplied predicate so callers can validate
 * against a hypothetical post-change configuration.
 */
public final class DependencyResolver {

    private DependencyResolver() {
    }

    /**
     * Validate {@code targetId} and transitively every enabled dependency.
     * Failures carry distinguishable codes and context (who requires whom, versions).
     */
    public static void validate(String targetId,
                                Map<String, ExtensionDefinition> definitions,
                                Predicate<String> isEnabledInTarget) {
        if (!definitions.containsKey(targetId)) {
            throw new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION,
                    "unknown extension: " + targetId, Map.of("extensionId", targetId));
        }
        // DFS: visit stack enables deterministic cycle reporting.
        Deque<String> stack = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        dfs(targetId, definitions, isEnabledInTarget, stack, visited);
    }

    private static void dfs(String id,
                            Map<String, ExtensionDefinition> definitions,
                            Predicate<String> isEnabledInTarget,
                            Deque<String> stack,
                            Set<String> visited) {
        ExtensionDefinition def = definitions.get(id);
        if (def == null) {
            throw new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION,
                    "unknown extension: " + id, Map.of("extensionId", id));
        }
        if (stack.contains(id)) {
            List<String> cyclePath = new ArrayList<>(stack);
            cyclePath.add(id);
            throw new ExtensionException(ExtensionErrorCode.CYCLIC_DEPENDENCY,
                    "cyclic dependency detected: " + String.join(" -> ", cyclePath),
                    Map.of("cycle", cyclePath, "extensionId", id));
        }
        if (visited.contains(id)) {
            return;
        }
        stack.push(id);
        for (DependencySpec dep : def.dependencies()) {
            String depId = dep.extensionId();
            ExtensionDefinition depDef = definitions.get(depId);
            if (depDef == null) {
                throw new ExtensionException(ExtensionErrorCode.DEPENDENCY_NOT_FOUND,
                        "extension '" + id + "' requires missing dependency '" + depId + "'",
                        Map.of("extensionId", id, "dependencyId", depId,
                                "requiredRange", dep.acceptedRange().toString()));
            }
            if (!dep.acceptedRange().contains(depDef.version())) {
                throw new ExtensionException(ExtensionErrorCode.VERSION_INCOMPATIBLE,
                        "extension '" + id + "' requires '" + depId + "' " + dep.acceptedRange()
                                + " but version is " + depDef.version(),
                        Map.of("extensionId", id, "dependencyId", depId,
                                "requiredRange", dep.acceptedRange().toString(),
                                "actualVersion", depDef.version().toString()));
            }
            if (!isEnabledInTarget.test(depId)) {
                throw new ExtensionException(ExtensionErrorCode.DEPENDENCY_NOT_READY,
                        "extension '" + id + "' requires dependency '" + depId
                                + "' which is not enabled",
                        Map.of("extensionId", id, "dependencyId", depId,
                                "dependencyVersion", depDef.version().toString()));
            }
            dfs(depId, definitions, isEnabledInTarget, stack, visited);
        }
        stack.pop();
        visited.add(id);
    }

    /**
     * Kahn topological order over the given ids restricted to edges within the set.
     * Used to initialize enabled extensions dependencies-first.
     */
    public static List<String> topologicalOrder(Set<String> ids,
                                                Map<String, ExtensionDefinition> definitions) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (String id : ids) {
            indegree.putIfAbsent(id, 0);
        }
        for (String id : ids) {
            ExtensionDefinition def = definitions.get(id);
            for (DependencySpec dep : def.dependencies()) {
                if (ids.contains(dep.extensionId())) {
                    indegree.merge(id, 1, Integer::sum);
                    dependents.computeIfAbsent(dep.extensionId(), k -> new ArrayList<>()).add(id);
                }
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                int remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (order.size() != ids.size()) {
            List<String> unresolved = new ArrayList<>(ids);
            unresolved.removeAll(order);
            throw new ExtensionException(ExtensionErrorCode.CYCLIC_DEPENDENCY,
                    "cyclic dependency detected among enabled extensions: " + unresolved,
                    Map.of("unresolved", unresolved));
        }
        return order;
    }
}
