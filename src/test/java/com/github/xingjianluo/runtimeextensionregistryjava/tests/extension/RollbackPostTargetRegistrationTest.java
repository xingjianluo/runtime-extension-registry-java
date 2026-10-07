package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.DependencyRequirement;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionChange;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStateSnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回退目标版本之后又有新扩展注册的场景：
 * <ul>
 *   <li>回退成功后，对外可见的扩展集合/启停/配置与目标版本逐项一致，
 *       目标版本之后才注册的扩展一并退出对外状态（不复活、不多出）；</li>
 *   <li>目标内扩展当前依赖了"额外"扩展、移除后无法就绪时，整体拒绝并给出
 *       可区分原因（ROLLBACK_REJECTED + reason），当前状态原样可用、历史不推进；</li>
 *   <li>反复"注册额外扩展 -> 回退"收敛到同一对外状态，初始化/关闭次数可对账。</li>
 * </ul>
 */
class RollbackPostTargetRegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackPostTargetRegistrationTest.class);

    /** 记录初始化/关闭次数并持有生效配置的探针扩展。 */
    static class Probe implements Extension {
        final AtomicInteger initCount;
        final AtomicInteger shutdownCount;
        volatile Map<String, Object> activeConfig = Map.of();

        Probe(AtomicInteger initCount, AtomicInteger shutdownCount) {
            this.initCount = initCount;
            this.shutdownCount = shutdownCount;
        }

        @Override
        public void initialize(ExtensionContext context) {
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
    private AtomicInteger initsA;
    private AtomicInteger shutdownsA;
    private AtomicInteger initsX;
    private AtomicInteger shutdownsX;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        initsA = new AtomicInteger();
        shutdownsA = new AtomicInteger();
        initsX = new AtomicInteger();
        shutdownsX = new AtomicInteger();
    }

    private ExtensionDefinition probe(String id, Map<String, Object> config, boolean enabled,
                                      AtomicInteger inits, AtomicInteger shutdowns) {
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled, config,
                () -> new Probe(inits, shutdowns));
    }

    /** 断言当前对外快照与目标历史版本逐项一致：id 集合、启停、配置。 */
    private void assertVisibleStateMatchesTarget(long targetVersion) {
        HistoryVersion target = registry.historyVersion(targetVersion);
        RegistrySnapshot snapshot = registry.snapshot();
        Map<String, ExtensionStatusView> visible = snapshot.extensions().stream()
                .collect(Collectors.toMap(ExtensionStatusView::id, v -> v));
        Map<String, ExtensionStateSnapshot> expected = target.extensions().stream()
                .collect(Collectors.toMap(ExtensionStateSnapshot::id, s -> s));
        log.info("judgement: visible ids {} must equal target v{} ids {}",
                visible.keySet(), targetVersion, expected.keySet());
        assertThat(visible.keySet()).isEqualTo(expected.keySet());
        for (ExtensionStateSnapshot state : target.extensions()) {
            ExtensionStatusView view = visible.get(state.id());
            log.info("judgement: id={} visible(enabled={}, config={}) must equal target(enabled={}, config={})",
                    state.id(), view.enabled(), view.config(), state.enabled(), state.config());
            assertThat(view.enabled()).isEqualTo(state.enabled());
            assertThat(view.config()).isEqualTo(state.config());
        }
    }

    @Test
    void rollbackDropsExtensionsRegisteredAfterTargetAndMatchesTargetExactly() {
        // v1: a(k=a1, 启用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, shutdownsA)))));
        // v2: 目标版本之后新注册 x(k=x1, 启用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("x", Map.of("k", "x1"), true, initsX, shutdownsX)))));
        // v3: a 改配置（让回退对 a 也有真实恢复内容）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("a", Map.of("k", "a2")))));
        long generationBefore = registry.snapshot().generation();
        log.info("rollback input: targetVersion=1, currentGeneration={}, extra extension 'x' registered at v2; "
                + "judgement: after success visible state must equal v1 exactly (no 'x')", generationBefore);

        ChangeOutcome outcome = registry.rollback(1);

        // 回退成功生成更新编号，历史不被改写
        assertThat(outcome.generation()).isGreaterThan(generationBefore);
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L, 4L);

        // 对外状态与目标版本逐项一致：只剩 a(k=a1, 启用)，x 不再对外提供
        assertVisibleStateMatchesTarget(1);
        assertThat(registry.status("x")).isEmpty();
        log.info("judgement: invoke 'x' after rollback expect=EXTENSION_NOT_FOUND");
        assertThatThrownBy(() -> registry.invoke(new ExtensionInvocation("x", Map.of())))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.EXTENSION_NOT_FOUND);
        // a 恢复目标配置且可正常执行
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("a", Map.of())).data();
        assertThat(data.get("k")).isEqualTo("a1");

        // 回退提交的历史 diff 体现 x 被移除与 a 配置回改
        HistoryVersion v4 = registry.historyVersion(4);
        log.info("v4 (rollback commit) changes={}", v4.changes());
        assertThat(v4.changes()).anySatisfy(c -> {
            assertThat(c.extensionId()).isEqualTo("x");
            assertThat(c.kind()).isEqualTo(ExtensionChange.ChangeKind.REMOVED);
        });
        assertThat(v4.changes()).anySatisfy(c -> {
            assertThat(c.extensionId()).isEqualTo("a");
            assertThat(c.kind()).isEqualTo(ExtensionChange.ChangeKind.CONFIG_CHANGED);
        });

        // 生命周期对账：x 初始化 1 次、随回退关闭 1 次；a 每次配置变化恰好初始化 1 次、旧实例成对关闭
        log.info("initsA={} shutdownsA={} initsX={} shutdownsX={} "
                        + "(expect a: 3 inits / 2 shutdowns; x: 1 init / 1 shutdown)",
                initsA.get(), shutdownsA.get(), initsX.get(), shutdownsX.get());
        assertThat(initsA.get()).isEqualTo(3);
        assertThat(shutdownsA.get()).isEqualTo(2);
        assertThat(initsX.get()).isEqualTo(1);
        assertThat(shutdownsX.get()).isEqualTo(1);
    }

    @Test
    void rollbackRejectedWhenTargetExtensionNowDependsOnPostTargetExtension() {
        // v1: a(k=a1, 启用，无依赖)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, shutdownsA)))));
        // v2: 目标版本之后新注册 dep
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("dep", Map.of("k", "d1"), true, initsX, shutdownsX)))));
        // v3: 重新定义 a，使其当前依赖 dep（dep 是目标版本里没有的"额外"扩展）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RemoveExtension("a"),
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "a", Version.parse("1.0.0"),
                        List.of(new DependencyRequirement("dep", "1.0.0")), true, Map.of("k", "a3"),
                        () -> new Probe(initsA, shutdownsA))))));
        long generationBefore = registry.snapshot().generation();
        int historyBefore = registry.history().size();
        int initsABefore = initsA.get();
        int initsXBefore = initsX.get();
        log.info("rollback input: targetVersion=1, but current 'a' depends on post-target 'dep'; "
                + "judgement: expect ROLLBACK_REJECTED(reason=MISSING_DEPENDENCY), "
                + "current state keeps serving, history not advanced");

        assertThatThrownBy(() -> registry.rollback(1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    // 与 VERSION_NOT_FOUND、普通变更失败（INVALID_CONFIG 等）明确区分
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_REJECTED);
                    assertThat(ee.context()).containsEntry("reason", "MISSING_DEPENDENCY");
                    assertThat(ee.context()).containsEntry("version", 1L);
                });

        // 当前状态原样可用：世代不变、两个扩展都在且就绪、均未重建
        assertThat(registry.snapshot().generation()).isEqualTo(generationBefore);
        assertThat(registry.status("a").orElseThrow().ready()).isTrue();
        assertThat(registry.status("dep").orElseThrow().ready()).isTrue();
        assertThat(initsA.get()).isEqualTo(initsABefore);
        assertThat(initsX.get()).isEqualTo(initsXBefore);
        Map<?, ?> dataA = (Map<?, ?>) registry.invoke(new ExtensionInvocation("a", Map.of())).data();
        assertThat(dataA.get("k")).isEqualTo("a3");
        // 历史既不推进也不改写
        assertThat(registry.history()).hasSize(historyBefore);
        assertThat(registry.historyVersion(1).extensions())
                .anySatisfy(s -> {
                    assertThat(s.id()).isEqualTo("a");
                    assertThat(s.config()).isEqualTo(Map.of("k", "a1"));
                });
        // 拒绝原因与"版本查不到"可区分
        log.info("judgement: rollback to never-existed version=999 expect=VERSION_NOT_FOUND "
                + "(distinct from ROLLBACK_REJECTED above)");
        assertThatThrownBy(() -> registry.rollback(999))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.VERSION_NOT_FOUND);
    }

    @Test
    void repeatedRegisterExtraThenRollbackConvergesToSameVisibleState() {
        // v1: a(k=a1, 启用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, shutdownsA)))));
        int initsAAfterV1 = initsA.get();

        // 多轮：注册额外扩展 x -> 回退 v1；每轮结束后对外状态都必须收敛回 v1
        for (int round = 1; round <= 3; round++) {
            registry.apply(new ChangeSet(List.of(
                    new ChangeCommand.RegisterDefinition(
                            probe("x", Map.of("k", "x" + round), true, initsX, shutdownsX)))));
            registry.rollback(1);
            RegistrySnapshot snapshot = registry.snapshot();
            log.info("round={}: visible ids={} (judgement: must be exactly [a], config k=a1)",
                    round, snapshot.extensions().stream().map(ExtensionStatusView::id).toList());
            assertVisibleStateMatchesTarget(1);
            assertThat(registry.status("x")).isEmpty();
        }

        // a 的配置从未真正变化（v1 状态即当前状态），回退不得重复初始化 a
        log.info("initsA={} (expect {}: 未受影响不重复初始化), initsX={} shutdownsX={} (expect 3/3: 每轮一成对)",
                initsA.get(), initsAAfterV1, initsX.get(), shutdownsX.get());
        assertThat(initsA.get()).isEqualTo(initsAAfterV1);
        assertThat(initsX.get()).isEqualTo(3);
        assertThat(shutdownsX.get()).isEqualTo(3);
        // 历史完整：1 次初始注册 + 3 轮 * (注册 + 回退) = 7 个版本，编号连续递增
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }
}
