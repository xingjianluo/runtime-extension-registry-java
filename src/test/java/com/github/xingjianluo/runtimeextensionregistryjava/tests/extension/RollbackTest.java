package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史版本回退：成功后状态与目标一致并生成更新编号；目标含已不存在扩展、
 * 或按当前定义无法就绪时整体拒绝（ROLLBACK_REJECTED）且当前状态原样可用；
 * 未受影响扩展不重复初始化；反复回退/再改能稳定收敛。
 */
class RollbackTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackTest.class);

    /** 记录初始化次数并持有生效配置的探针扩展；validK 非空时校验配置 k 必须等于它。 */
    static class Probe implements Extension {
        final AtomicInteger initCount;
        final String validK;
        volatile Map<String, Object> activeConfig = Map.of();

        Probe(AtomicInteger initCount, String validK) {
            this.initCount = initCount;
            this.validK = validK;
        }

        @Override
        public void initialize(ExtensionContext context) {
            if (validK != null && !validK.equals(context.config().get("k"))) {
                throw new IllegalArgumentException("k must be " + validK);
            }
            activeConfig = Map.copyOf(context.config());
            initCount.incrementAndGet();
        }

        @Override
        public Object execute(Map<String, Object> input) {
            return activeConfig;
        }
    }

    private DefaultExtensionRegistry registry;
    private AtomicInteger initsA;
    private AtomicInteger initsB;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        initsA = new AtomicInteger();
        initsB = new AtomicInteger();
    }

    private ExtensionDefinition probe(String id, Map<String, Object> config, boolean enabled,
                                      AtomicInteger inits, String validK) {
        ExtensionFactory factory = () -> new Probe(inits, validK);
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled, config, factory);
    }

    @Test
    void rollbackRestoresTargetStateAndRecordsNewerVersion() {
        // v1: a(k=a1, 启用), b(k=b1, 停用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, null)),
                new ChangeCommand.RegisterDefinition(probe("b", Map.of("k", "b1"), false, initsB, null)))));
        // v2: a 改配置，b 启用
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("a", Map.of("k", "a2")),
                new ChangeCommand.SetEnabled("b", true))));
        long generationBeforeRollback = registry.snapshot().generation();
        log.info("rollback input: targetVersion=1, currentGeneration={}, "
                + "judgement: enabled/config of a,b must match v1 afterwards", generationBeforeRollback);

        ChangeOutcome outcome = registry.rollback(1);

        // 生成比当前更新的编号，且历史不被覆盖
        assertThat(outcome.generation()).isGreaterThan(generationBeforeRollback);
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L);
        assertThat(registry.historyVersion(1).changes()).isNotEmpty();

        // 对外状态与目标版本一致：a(k=a1, 启用), b(停用)
        ExtensionStatusView a = registry.status("a").orElseThrow();
        ExtensionStatusView b = registry.status("b").orElseThrow();
        log.info("after rollback: a(enabled={}, config={}), b(enabled={})",
                a.enabled(), a.config(), b.enabled());
        assertThat(a.enabled()).isTrue();
        assertThat(a.config()).isEqualTo(Map.of("k", "a1"));
        assertThat(b.enabled()).isFalse();

        // 回退本身也是一次变更，留存了新的历史版本，其 diff 体现 a 配置回改与 b 停用
        HistoryVersion v3 = registry.historyVersion(3);
        log.info("v3 (rollback commit) changes={}", v3.changes());
        assertThat(v3.changes()).anySatisfy(c -> {
            assertThat(c.extensionId()).isEqualTo("a");
            assertThat(c.kind()).isEqualTo(
                    com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionChange.ChangeKind.CONFIG_CHANGED);
            assertThat(c.newConfig()).isEqualTo(Map.of("k", "a1"));
        });
        assertThat(v3.changes()).anySatisfy(c -> {
            assertThat(c.extensionId()).isEqualTo("b");
            assertThat(c.kind()).isEqualTo(
                    com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionChange.ChangeKind.DISABLED);
        });
    }

    @Test
    void rollbackDoesNotReinitializeUnaffectedExtensions() {
        // v1: a(k=a1), b(k=b1) 均启用
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, null)),
                new ChangeCommand.RegisterDefinition(probe("b", Map.of("k", "b1"), true, initsB, null)))));
        // v2: 仅 a 改配置
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("a", Map.of("k", "a2")))));
        assertThat(initsA.get()).isEqualTo(2);
        assertThat(initsB.get()).isEqualTo(1);

        // 回退到 v1：只有 a 的配置变回，b 完全不受影响
        registry.rollback(1);
        log.info("after rollback to 1: initsA={} (expect 3: 一次成功变更对应一次初始化), initsB={} (expect 1: 未受影响不重建)",
                initsA.get(), initsB.get());
        assertThat(initsA.get()).isEqualTo(3);
        assertThat(initsB.get()).isEqualTo(1);
    }

    @Test
    void rollbackToVersionWithRemovedExtensionIsRejectedAndCurrentStateUntouched() {
        // v1: a + ghost 均启用
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, null)),
                new ChangeCommand.RegisterDefinition(probe("ghost", Map.of("k", "g1"), true,
                        new AtomicInteger(), null)))));
        // v2: 移除 ghost
        registry.apply(new ChangeSet(List.of(new ChangeCommand.RemoveExtension("ghost"))));
        long generationBefore = registry.snapshot().generation();
        int initsBefore = initsA.get();

        log.info("rollback input: targetVersion=1 (contains removed 'ghost'), "
                + "judgement: expect ROLLBACK_REJECTED, current state must stay serving");
        assertThatThrownBy(() -> registry.rollback(1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_REJECTED);
                    assertThat(ee.context()).containsEntry("reason", "EXTENSIONS_NO_LONGER_REGISTERED");
                    assertThat(String.valueOf(ee.context().get("missingExtensions"))).contains("ghost");
                });

        // 当前状态原样可用：世代不变、a 未重建、仍可执行
        assertThat(registry.snapshot().generation()).isEqualTo(generationBefore);
        assertThat(initsA.get()).isEqualTo(initsBefore);
        assertThat(registry.status("ghost")).isEmpty();
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("a", Map.of())).data();
        assertThat(data.get("k")).isEqualTo("a1");
        // 失败的回退不产生新历史版本
        assertThat(registry.history()).hasSize(2);
    }

    @Test
    void rollbackFailingCurrentValidationIsRejectedWithDistinguishableReason() {
        // v1: p 使用宽松工厂，配置 k=anything
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("p", Map.of("k", "anything"), true,
                        initsA, null)))));
        // v2: 移除 p 并以"只接受 k=fixed"的严格工厂重新注册（当前定义已变化）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RemoveExtension("p"),
                new ChangeCommand.RegisterDefinition(probe("p", Map.of("k", "fixed"), true,
                        initsB, "fixed")))));
        long generationBefore = registry.snapshot().generation();

        // 回退到 v1：目标配置 k=anything 在当前的严格工厂下无法初始化 -> 整体拒绝
        log.info("rollback input: targetVersion=1 (config k=anything), current factory requires k=fixed, "
                + "judgement: expect ROLLBACK_REJECTED with reason=INVALID_CONFIG");
        assertThatThrownBy(() -> registry.rollback(1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_REJECTED);
                    assertThat(ee.context()).containsEntry("reason", "INVALID_CONFIG");
                });

        // 当前状态原样可用
        assertThat(registry.snapshot().generation()).isEqualTo(generationBefore);
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("p", Map.of())).data();
        assertThat(data.get("k")).isEqualTo("fixed");
        assertThat(registry.history()).hasSize(2);
    }

    @Test
    void rollbackToEvictedOrNeverExistedVersionIsVersionNotFound() {
        DefaultExtensionRegistry small = new DefaultExtensionRegistry(2);
        small.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "v0"), true,
                        new AtomicInteger(), null)))));
        for (int i = 1; i <= 3; i++) {
            small.apply(new ChangeSet(List.of(
                    new ChangeCommand.UpdateConfig("a", Map.of("k", "v" + i)))));
        }
        // 留存 [3,4]；1、2 已淘汰，999 从未存在
        for (long missing : new long[]{1L, 2L, 999L}) {
            log.info("rollback input: targetVersion={} expect=VERSION_NOT_FOUND", missing);
            assertThatThrownBy(() -> small.rollback(missing))
                    .isInstanceOf(ExtensionException.class)
                    .extracting(ex -> ((ExtensionException) ex).code())
                    .isEqualTo(ErrorCode.VERSION_NOT_FOUND);
        }
        // 拒绝回退后当前状态照常服务
        assertThat(small.status("a").orElseThrow().ready()).isTrue();
    }

    @Test
    void repeatedRollbackAndChangeConvergesToConsistentState() {
        // v1: a(k=a1, 启用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, null)))));
        // 多轮：改到 a2 -> 回退 v1 -> 改到 a3 -> 回退 v1 ...
        for (int round = 1; round <= 3; round++) {
            registry.apply(new ChangeSet(List.of(
                    new ChangeCommand.UpdateConfig("a", Map.of("k", "a-next-" + round)))));
            registry.rollback(1);
            ExtensionStatusView a = registry.status("a").orElseThrow();
            Map<?, ?> executed = (Map<?, ?>) registry.invoke(new ExtensionInvocation("a", Map.of())).data();
            log.info("round={}: view config={} executed config={} (judgement: both must equal k=a1)",
                    round, a.config(), executed);
            assertThat(a.config()).isEqualTo(Map.of("k", "a1"));
            assertThat(executed.get("k")).isEqualTo("a1");
        }
        // 每次成功变更（改配置/回退）都改变了 a 的配置 -> 各初始化一次：1 + 3*2
        log.info("initsA={} (expect 7 = 1 initial + 3 rounds * 2 successful changes)", initsA.get());
        assertThat(initsA.get()).isEqualTo(7);
        // 历史完整留存：1 次注册 + 3 轮 * 2 次提交 = 7 个版本，编号连续
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }
}
