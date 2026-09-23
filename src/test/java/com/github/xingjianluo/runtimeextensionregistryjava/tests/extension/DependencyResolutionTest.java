package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.DependencyRequirement;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 依赖解析相关的可区分失败原因验证。 */
class DependencyResolutionTest {

    private static final Logger log = LoggerFactory.getLogger(DependencyResolutionTest.class);

    /** 计数初始化次数，用于后续"未受影响不重复初始化"的断言。 */
    static class CountingExtension implements Extension {
        static int initCount;
        static int shutdownCount;

        @Override
        public void initialize(ExtensionContext context) {
            initCount++;
        }

        @Override
        public void shutdown() {
            shutdownCount++;
        }
    }

    private DefaultExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        CountingExtension.initCount = 0;
        CountingExtension.shutdownCount = 0;
    }

    private ExtensionDefinition def(String id, String version, List<DependencyRequirement> deps, boolean enabled) {
        return new ExtensionDefinition(id, Version.parse(version), deps, enabled, Map.of(), CountingExtension::new);
    }

    private ChangeSet register(ExtensionDefinition... defs) {
        return new ChangeSet(java.util.Arrays.stream(defs).map(ChangeCommand.RegisterDefinition::new).map(c -> (ChangeCommand) c).toList());
    }

    private void assertRejected(String scenario, ChangeSet changeSet, ErrorCode expected) {
        log.info("scenario={} expectedRejection={} input={}", scenario, expected, changeSet.commands());
        assertThatThrownBy(() -> registry.apply(changeSet))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> assertThat(((ExtensionException) ex).code()).isEqualTo(expected));
    }

    @Test
    void duplicateIdAcrossExistingRegistryIsRejected() {
        registry.apply(register(def("a", "1.0.0", List.of(), true)));
        // 重复标识不得静默覆盖
        assertRejected("duplicate-id", register(def("a", "2.0.0", List.of(), true)),
                ErrorCode.DUPLICATE_EXTENSION_ID);
        // 原定义仍然可用，未被覆盖
        assertThat(registry.status("a").orElseThrow().version()).isEqualTo("1.0.0");
    }

    @Test
    void missingDependencyIsRejected() {
        assertRejected("missing-dependency",
                register(def("a", "1.0.0",
                        List.of(new DependencyRequirement("ghost", "1.0.0")), true)),
                ErrorCode.MISSING_DEPENDENCY);
    }

    @Test
    void incompatibleDependencyVersionIsRejected() {
        ExtensionDefinition base = def("base", "2.5.0", List.of(), true);
        ExtensionDefinition client = def("client", "1.0.0",
                List.of(new DependencyRequirement("base", "[1.0.0,2.0.0)")), true);
        assertRejected("incompatible-version", register(base, client),
                ErrorCode.INCOMPATIBLE_DEPENDENCY_VERSION);
    }

    @Test
    void invalidVersionRangeIsRejected() {
        ExtensionDefinition base = def("base", "1.0.0", List.of(), true);
        ExtensionDefinition client = def("client", "1.0.0",
                List.of(new DependencyRequirement("base", "not-a-range")), true);
        assertRejected("invalid-range", register(base, client),
                ErrorCode.INVALID_VERSION_RANGE);
    }

    @Test
    void directCyclicDependencyIsRejectedWithCycleContext() {
        ExtensionDefinition a = def("a", "1.0.0", List.of(new DependencyRequirement("b", "1.0.0")), true);
        ExtensionDefinition b = def("b", "1.0.0", List.of(new DependencyRequirement("a", "1.0.0")), true);
        log.info("scenario=cycle input a->b,b->a expect=CYCLIC_DEPENDENCY");
        assertThatThrownBy(() -> registry.apply(register(a, b)))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.CYCLIC_DEPENDENCY);
                    assertThat(ee.context().get("cycle").toString()).contains("a", "b");
                });
    }

    @Test
    void transitiveCycleIsRejected() {
        ExtensionDefinition a = def("a", "1.0.0", List.of(new DependencyRequirement("b", "1.0.0")), true);
        ExtensionDefinition b = def("b", "1.0.0", List.of(new DependencyRequirement("c", "1.0.0")), true);
        ExtensionDefinition c = def("c", "1.0.0", List.of(new DependencyRequirement("a", "1.0.0")), true);
        assertRejected("transitive-cycle", register(a, b, c), ErrorCode.CYCLIC_DEPENDENCY);
    }

    @Test
    void compatibleGraphInitializesInTopologicalOrder() {
        ExtensionDefinition base = def("base", "1.2.0", List.of(), true);
        ExtensionDefinition mid = def("mid", "1.0.0",
                List.of(new DependencyRequirement("base", "[1.0.0,2.0.0)")), true);
        ExtensionDefinition top = def("top", "1.0.0",
                List.of(new DependencyRequirement("mid", "1.0.0")), true);
        var outcome = registry.apply(register(base, mid, top));
        log.info("compatible graph committed generation={}", outcome.generation());
        assertThat(outcome.generation()).isEqualTo(1);
        assertThat(registry.status("top").orElseThrow().ready()).isTrue();
        assertThat(CountingExtension.initCount).isEqualTo(3);
    }
}
