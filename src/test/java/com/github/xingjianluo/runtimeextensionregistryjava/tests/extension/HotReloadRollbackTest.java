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
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配置热更新、原子生效与整体回滚语义验证。 */
class HotReloadRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(HotReloadRollbackTest.class);

    /** 记录初始化/关闭次数并持有当前配置的扩展。 */
    static class ProbeExtension implements Extension {
        final AtomicInteger initCount;
        final AtomicInteger shutdownCount;
        final Map<String, Object> validConfig;
        volatile Map<String, Object> activeConfig = Map.of();

        ProbeExtension(AtomicInteger initCount, AtomicInteger shutdownCount, Map<String, Object> validConfig) {
            this.initCount = initCount;
            this.shutdownCount = shutdownCount;
            this.validConfig = validConfig;
        }

        @Override
        public void initialize(ExtensionContext context) {
            for (String key : validConfig.keySet()) {
                Object value = context.config().get(key);
                if (!(value instanceof String s0) || s0.isBlank() || "WRONG".equals(s0)) {
                    throw new IllegalArgumentException("missing/invalid config key: " + key);
                }
            }
            Object boom = context.config().get("simulateInitFault");
            if (Boolean.TRUE.equals(boom)) {
                throw new IllegalStateException("simulated fault");
            }
            activeConfig = Map.copyOf(context.config());
            initCount.incrementAndGet();
        }

        @Override
        public Object execute(Map<String, Object> input) {
            return activeConfig;
        }

        @Override
        public void shutdown() {
            shutdownCount.incrementAndGet();
        }
    }

    private DefaultExtensionRegistry registry;
    private AtomicInteger inits;
    private AtomicInteger shutdowns;

    private ExtensionDefinition probe(String id, Map<String, Object> config, boolean enabled) {
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled, config,
                () -> new ProbeExtension(inits, shutdowns, Map.of("k", "v")));
    }

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        inits = new AtomicInteger();
        shutdowns = new AtomicInteger();
    }

    @Test
    void validConfigHotReloadAtomicallyReplacesInstance() {
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("p", Map.of("k", "v"), true)))));
        assertThat(inits.get()).isEqualTo(1);

        long before = registry.snapshot().generation();
        var outcome = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("p", Map.of("k", "v", "extra", "n")))));
        log.info("hot-reload: oldGeneration={} newGeneration={} inits={} shutdowns={}",
                before, outcome.generation(), inits.get(), shutdowns.get());

        assertThat(outcome.generation()).isGreaterThan(before);
        assertThat(inits.get()).isEqualTo(2);          // 重新初始化恰好一次
        assertThat(shutdowns.get()).isEqualTo(1);     // 旧实例恰好关闭一次
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("p", Map.of())).data();
        assertThat(data.get("extra")).isEqualTo("n");
    }

    @Test
    void unrelatedExtensionIsNotReinitializedOnAnotherChange() {
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "v"), true)),
                new ChangeCommand.RegisterDefinition(probe("b", Map.of("k", "v"), true)))));
        assertThat(inits.get()).isEqualTo(2);

        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("a", Map.of("k", "v2")))));
        log.info("partial hot-reload: inits={} (expect 3) shutdowns={} (expect 1)",
                inits.get(), shutdowns.get());
        assertThat(inits.get()).isEqualTo(3);  // 仅 a 重新初始化
        assertThat(shutdowns.get()).isEqualTo(1);
        // b 仍可正常服务
        assertThat(registry.status("b").orElseThrow().ready()).isTrue();
    }

    @Test
    void invalidConfigRejectsBatchAndKeepsOldStateServing() {
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("p", Map.of("k", "v"), true)))));
        long generationBefore = registry.snapshot().generation();

        log.info("rollback scenario inputConfig={} expect=INVALID_CONFIG", Map.of("k", "WRONG"));
        assertThatThrownBy(() -> registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("p", Map.of("k", "WRONG"))))))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.INVALID_CONFIG);
                    assertThat(ee.context()).containsKey("extensionId");
                });

        // 世代不变；旧实例继续可用；失败的新实例已被丢弃，旧实例未被关闭
        assertThat(registry.snapshot().generation()).isEqualTo(generationBefore);
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("p", Map.of())).data();
        assertThat(data.get("k")).isEqualTo("v");
        assertThat(shutdowns.get()).isZero();
    }

    @Test
    void initializationFaultIsDistinguishedFromInvalidConfigAndRollsBack() {
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("p", Map.of("k", "v"), true)))));

        log.info("init-fault scenario config contains simulateInitFault=true expect=INITIALIZATION_FAILED");
        assertThatThrownBy(() -> registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("p", Map.of("k", "v", "simulateInitFault", true))))))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.INITIALIZATION_FAILED);

        assertThat(registry.status("p").orElseThrow().ready()).isTrue();
    }

    @Test
    void disableThenEnableLifecycle() {
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("p", Map.of("k", "v"), true)))));
        registry.apply(new ChangeSet(List.of(new ChangeCommand.SetEnabled("p", false))));
        assertThat(registry.status("p").orElseThrow().enabled()).isFalse();

        log.info("disabled invoke expect=EXTENSION_DISABLED");
        assertThatThrownBy(() -> registry.invoke(new ExtensionInvocation("p", Map.of())))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.EXTENSION_DISABLED);

        registry.apply(new ChangeSet(List.of(new ChangeCommand.SetEnabled("p", true))));
        assertThat(registry.invoke(new ExtensionInvocation("p", Map.of())).data())
                .isInstanceOf(Map.class);
    }

    @Test
    void batchIsAllOrNothingWhenSecondCommandFails() {
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "v"), true)))));

        // 同一批次中：a 的合法配置更新 + b 的非法配置；b 不存在 -> EXTENSION_NOT_FOUND，整体拒绝
        ChangeSet mixed = new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("a", Map.of("k", "changed")),
                new ChangeCommand.UpdateConfig("ghost", Map.of())));
        log.info("atomic-batch scenario commands={} expect=EXTENSION_NOT_FOUND", mixed.commands());
        assertThatThrownBy(() -> registry.apply(mixed))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.EXTENSION_NOT_FOUND);
        // a 的配置不得部分生效
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("a", Map.of())).data();
        assertThat(data.get("k")).isEqualTo("v");
    }

    @Test
    void dependentDisabledMakesDependantNotReadyButKeepsServingOthers() {
        AtomicInteger depInits = new AtomicInteger();
        ExtensionDefinition dep = new ExtensionDefinition("dep", Version.parse("1.0.0"),
                List.of(), true, Map.of(), () -> new ProbeExtension(depInits, new AtomicInteger(), Map.of()));
        ExtensionDefinition client = new ExtensionDefinition("client", Version.parse("1.0.0"),
                List.of(new DependencyRequirement("dep", "1.0.0")), true, Map.of("k", "v"),
                () -> new ProbeExtension(inits, shutdowns, Map.of("k", "v")));
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(dep),
                new ChangeCommand.RegisterDefinition(client))));

        registry.apply(new ChangeSet(List.of(new ChangeCommand.SetEnabled("dep", false))));
        assertThat(registry.status("client").orElseThrow().ready()).isFalse();
        assertThat(registry.status("client").orElseThrow().blockedBy()).containsExactly("dep");

        log.info("not-ready invoke expect=DEPENDENCY_NOT_READY");
        assertThatThrownBy(() -> registry.invoke(new ExtensionInvocation("client", Map.of())))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.DEPENDENCY_NOT_READY);
                    assertThat(ee.context().get("blockedBy").toString()).contains("dep");
                });
    }
}
