package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.github.xingjianluo.runtimeextensionregistryjava.core.DependencyResolver;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.DependencySpec;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyResolverTest {

    private static final Logger LOG = LoggerFactory.getLogger(DependencyResolverTest.class);

    private static ExtensionDefinition def(String id, String version, DependencySpec... deps) {
        return new ExtensionDefinition(id, version, "test", List.of(deps), Map.of());
    }

    private static DependencySpec dep(String id, String range) {
        return new DependencySpec(id, new com.github.xingjianluo.runtimeextensionregistryjava.model.VersionRange(range));
    }

    @Test
    void missingDependencyIsRejectedWithDependencyNotFound() {
        Map<String, ExtensionDefinition> defs = Map.of("a", def("a", "1.0.0", dep("missing", "[1.0.0,2.0.0)")));
        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> DependencyResolver.validate("a", defs, id -> true));
        LOG.info("[input target=a deps={}] decision: rejected code={} context={}",
                defs.get("a").dependencies(), ex.errorCode(), ex.context());
        assertEquals(ExtensionErrorCode.DEPENDENCY_NOT_FOUND, ex.errorCode());
        assertEquals("missing", ex.context().get("dependencyId"));
    }

    @Test
    void incompatibleVersionIsRejectedWithVersionIncompatible() {
        Map<String, ExtensionDefinition> defs = Map.of(
                "a", def("a", "1.0.0", dep("b", "[2.0.0,3.0.0)")),
                "b", def("b", "1.5.0"));
        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> DependencyResolver.validate("a", defs, id -> true));
        LOG.info("[input target=a required=b[2.0.0,3.0.0) actual=b1.5.0] decision: rejected code={} context={}",
                ex.errorCode(), ex.context());
        assertEquals(ExtensionErrorCode.VERSION_INCOMPATIBLE, ex.errorCode());
        assertEquals("1.5.0", ex.context().get("actualVersion"));
    }

    @Test
    void disabledDependencyIsRejectedWithDependencyNotReady() {
        Map<String, ExtensionDefinition> defs = Map.of(
                "a", def("a", "1.0.0", dep("b", "[1.0.0,2.0.0)")),
                "b", def("b", "1.2.0"));
        Predicate<String> onlyAEnabled = "a"::equals;
        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> DependencyResolver.validate("a", defs, onlyAEnabled));
        LOG.info("[input target=a enabledInTarget={a}] decision: rejected code={} context={}",
                ex.errorCode(), ex.context());
        assertEquals(ExtensionErrorCode.DEPENDENCY_NOT_READY, ex.errorCode());
    }

    @Test
    void cyclicDependencyIsRejectedWithCyclicDependency() {
        Map<String, ExtensionDefinition> defs = Map.of(
                "a", def("a", "1.0.0", dep("b", "[1.0.0,2.0.0)")),
                "b", def("b", "1.0.0", dep("c", "[1.0.0,2.0.0)")),
                "c", def("c", "1.0.0", dep("a", "[1.0.0,2.0.0)")));
        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> DependencyResolver.validate("a", defs, id -> true));
        LOG.info("[input cycle=a->b->c->a] decision: rejected code={} context={}",
                ex.errorCode(), ex.context());
        assertEquals(ExtensionErrorCode.CYCLIC_DEPENDENCY, ex.errorCode());

        ExtensionException topo = assertThrows(ExtensionException.class,
                () -> DependencyResolver.topologicalOrder(Set.of("a", "b", "c"), defs));
        assertEquals(ExtensionErrorCode.CYCLIC_DEPENDENCY, topo.errorCode());
    }

    @Test
    void validGraphPassesAndTopologicalOrderIsDependenciesFirst() {
        Map<String, ExtensionDefinition> defs = Map.of(
                "a", def("a", "1.0.0", dep("b", "[1.0.0,2.0.0)")),
                "b", def("b", "1.0.0", dep("c", "[1.0.0,2.0.0)")),
                "c", def("c", "1.0.0"));
        DependencyResolver.validate("a", defs, id -> true);
        List<String> order = DependencyResolver.topologicalOrder(Set.of("a", "b", "c"), defs);
        LOG.info("[input graph=a->b->c] decision: accepted topologicalOrder={}", order);
        assertTrue(order.indexOf("c") < order.indexOf("b"));
        assertTrue(order.indexOf("b") < order.indexOf("a"));
    }
}
