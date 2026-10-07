package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.DependencyRequirement;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStateSnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回退目标版本之后才注册了新扩展的场景：
 * <ol>
 *   <li>回退成功后，后注册的扩展必须一并移除，对外状态（扩展集合 + 启停 + 配置）
 *       与目标版本逐项一致，绝不多出目标版本里没有的扩展；</li>
 *   <li>目标扩展的当前定义依赖了后注册扩展时，回退整体拒绝（ROLLBACK_REJECTED，
 *       原因可与 VERSION_NOT_FOUND 及普通变更失败区分），当前状态原样可用、历史不推进；</li>
 *   <li>反复"注册新扩展 → 回退"能稳定收敛到同一目标状态，初始化/关闭次数对得上。</li>
 * </ol>
 */
class RollbackWithLaterRegistrationsTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackWithLaterRegistrationsTest.class);

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
    private AtomicInteger initsB;
    private AtomicInteger initsExtra;
    private AtomicInteger shutdownsExtra;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
        initsA = new AtomicInteger();
        shutdownsA = new AtomicInteger();
        initsB = new AtomicInteger();
        initsExtra = new AtomicInteger();
        shutdownsExtra = new AtomicInteger();
    }

    private ExtensionDefinition probe(String id, Map<String, Object> config, boolean enabled,
                                      AtomicInteger inits, AtomicInteger shutdowns) {
        ExtensionFactory factory = () -> new Probe(inits, shutdowns);
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled, config, factory);
    }

    /** 以当前注册为准、带依赖声明的探针定义（用于"重定义后依赖后注册扩展"场景）。 */
    private ExtensionDefinition probeWithDeps(String id, Map<String, Object> config,
                                              List<DependencyRequirement> deps,
                                              AtomicInteger inits, AtomicInteger shutdowns) {
        ExtensionFactory factory = () -> new Probe(inits, shutdowns);
        return new ExtensionDefinition(id, Version.parse("1.0.0"), deps, true, config, factory);
    }

    @Test
    void rollbackRemovesExtensionsRegisteredAfterTargetVersion() {
        // v1（回退目标）：a(k=a1, 启用), b(k=b1, 停用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, shutdownsA)),
                new ChangeCommand.RegisterDefinition(probe("b", Map.of("k", "b1"), false, initsB, new AtomicInteger())))));
        // v2：目标版本之后又注册了新扩展 extra，并改了 a 的配置、启用了 b
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("extra", Map.of("k", "x1"), true, initsExtra, shutdownsExtra)),
                new ChangeCommand.UpdateConfig("a", Map.of("k", "a2")),
                new ChangeCommand.SetEnabled("b", true))));
        long generationBefore = registry.snapshot().generation();
        HistoryVersion target = registry.historyVersion(1);
        log.info("rollback input: targetVersion=1 targetState={} currentGeneration={} "
                        + "judgement: after rollback visible set/enabled/config must equal target item by item, "
                        + "'extra' must be gone",
                target.extensions().stream()
                        .map(s -> s.id() + "(enabled=" + s.enabled() + ",config=" + s.config() + ")")
                        .toList(),
                generationBefore);

        ChangeOutcome outcome = registry.rollback(1);

        // 回退成功且生成更新编号；历史只增不改
        assertThat(outcome.generation()).isGreaterThan(generationBefore);
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L);

        // 对外状态与目标版本逐项一致：扩展集合不多不少
        RegistrySnapshot snapshot = registry.snapshot();
        Map<String, ExtensionStatusView> views = snapshot.extensions().stream()
                .collect(Collectors.toMap(ExtensionStatusView::id, v -> v));
        Map<String, ExtensionStateSnapshot> targetStates = target.extensions().stream()
                .collect(Collectors.toMap(ExtensionStateSnapshot::id, s -> s));
        log.info("after rollback: visible={} target={}", views.keySet(), targetStates.keySet());
        assertThat(views.keySet()).isEqualTo(targetStates.keySet());
        // 逐项核对启停与配置
        for (ExtensionStateSnapshot expected : target.extensions()) {
            ExtensionStatusView actual = views.get(expected.id());
            log.info("item check: id={} expected(enabled={}, config={}) actual(enabled={}, config={})",
                    expected.id(), expected.enabled(), expected.config(),
                    actual.enabled(), actual.config());
            assertThat(actual.enabled()).isEqualTo(expected.enabled());
            assertThat(actual.config()).isEqualTo(expected.config());
        }

        // 后注册的 extra 不再对外提供：状态查不到、执行被拒绝且原因可区分
        assertThat(registry.status("extra")).isEmpty();
        assertThatThrownBy(() -> registry.invoke(new ExtensionInvocation("extra", Map.of())))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.EXTENSION_NOT_FOUND);

        // 初始化/关闭对账：a 在 v2 改配置重建 1 次、回退再重建 1 次（共 3 次初始化、2 次旧实例关闭）；
        // b 仅启停变化不重建；extra 建 1 次、随回退关闭恰好 1 次
        log.info("init/shutdown accounting: initsA={} (expect 3) shutdownsA={} (expect 2) initsB={} (expect 1) "
                        + "initsExtra={} shutdownsExtra={} (expect 1/1)",
                initsA.get(), shutdownsA.get(), initsB.get(), initsExtra.get(), shutdownsExtra.get());
        assertThat(initsA.get()).isEqualTo(3);
        assertThat(shutdownsA.get()).isEqualTo(2);
        assertThat(initsB.get()).isEqualTo(1);
        assertThat(initsExtra.get()).isEqualTo(1);
        assertThat(shutdownsExtra.get()).isEqualTo(1);
    }

    @Test
    void rollbackRejectedWhenTargetExtensionNowDependsOnLaterRegisteredExtension() {
        // v1（回退目标）：a(k=a1, 启用)，无依赖
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, shutdownsA)))));
        // v2：目标版本之后注册了新扩展 extra
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("extra", Map.of("k", "x1"), true, initsExtra, shutdownsExtra)))));
        // v3：重定义 a，使其当前定义依赖后注册的 extra
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RemoveExtension("a"),
                new ChangeCommand.RegisterDefinition(probeWithDeps("a", Map.of("k", "a3"),
                        List.of(new DependencyRequirement("extra", "1.0.0")), initsA, shutdownsA)))));
        long generationBefore = registry.snapshot().generation();
        int historySizeBefore = registry.history().size();
        int initsExtraBefore = initsExtra.get();
        log.info("rollback input: targetVersion=1, but current definition of 'a' depends on later-registered "
                + "'extra'; judgement: expect ROLLBACK_REJECTED (reason=MISSING_DEPENDENCY), "
                + "distinct from VERSION_NOT_FOUND, current state keeps serving");

        // 回退要移除 extra，而 a 的当前定义依赖它 -> 无法整体生效 -> 整体拒绝
        assertThatThrownBy(() -> registry.rollback(1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    log.info("rejected as expected: code={} context={}", ee.code(), ee.context());
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_REJECTED);
                    // 与"版本查不到"明确区分
                    assertThat(ee.code()).isNotEqualTo(ErrorCode.VERSION_NOT_FOUND);
                    // 与普通变更失败（MISSING_DEPENDENCY 等）明确区分：对外统一为 ROLLBACK_REJECTED，
                    // 底层原因放在 context.reason 中
                    assertThat(ee.context()).containsEntry("reason", "MISSING_DEPENDENCY");
                });
        // 对照：版本查不到是另一种可区分的原因
        assertThatThrownBy(() -> registry.rollback(999))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.VERSION_NOT_FOUND);

        // 当前状态原样可用：世代不变、extra 未重建也未关闭、a 与 extra 都仍在对外提供
        assertThat(registry.snapshot().generation()).isEqualTo(generationBefore);
        assertThat(initsExtra.get()).isEqualTo(initsExtraBefore);
        assertThat(shutdownsExtra.get()).isZero();
        assertThat(registry.status("extra").orElseThrow().ready()).isTrue();
        assertThat(registry.status("a").orElseThrow().ready()).isTrue();
        Map<?, ?> data = (Map<?, ?>) registry.invoke(new ExtensionInvocation("a", Map.of())).data();
        assertThat(data.get("k")).isEqualTo("a3");
        // 被拒的回退不推进历史：既不新增也不改写
        assertThat(registry.history()).hasSize(historySizeBefore);
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void repeatedRegisterThenRollbackConvergesToSameTargetState() {
        // v1（回退目标）：a(k=a1, 启用)
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(probe("a", Map.of("k", "a1"), true, initsA, shutdownsA)))));
        HistoryVersion target = registry.historyVersion(1);

        int rounds = 3;
        for (int round = 1; round <= rounds; round++) {
            // 每轮：目标版本之后注册新扩展 extra-roundN，并改 a 的配置
            registry.apply(new ChangeSet(List.of(
                    new ChangeCommand.RegisterDefinition(probe("extra", Map.of("k", "x" + round), true,
                            initsExtra, shutdownsExtra)),
                    new ChangeCommand.UpdateConfig("a", Map.of("k", "a-round-" + round)))));
            registry.rollback(1);

            // 每轮回退后对外状态都必须收敛到同一个目标状态
            RegistrySnapshot snapshot = registry.snapshot();
            Map<String, ExtensionStatusView> views = snapshot.extensions().stream()
                    .collect(Collectors.toMap(ExtensionStatusView::id, v -> v));
            log.info("round={}: visible={} a.config={} (judgement: only {a}, config back to k=a1)",
                    round, views.keySet(), views.get("a").config());
            assertThat(views.keySet()).containsExactlyInAnyOrder("a");
            assertThat(views.get("a").enabled()).isEqualTo(target.extensions().get(0).enabled());
            assertThat(views.get("a").config()).isEqualTo(target.extensions().get(0).config());
            assertThat(registry.status("extra")).isEmpty();
        }

        // 初始化/关闭总对账：a 每轮改配置 + 回退各重建 1 次；extra 每轮建 1 次、关 1 次
        log.info("final accounting: initsA={} (expect {}) initsExtra={} shutdownsExtra={} (expect {}/{})",
                initsA.get(), 1 + 2 * rounds, initsExtra.get(), shutdownsExtra.get(), rounds, rounds);
        assertThat(initsA.get()).isEqualTo(1 + 2 * rounds);
        assertThat(initsExtra.get()).isEqualTo(rounds);
        assertThat(shutdownsExtra.get()).isEqualTo(rounds);
        // 历史完整推进：1 + 3 轮 * 2 次提交，编号连续、只增不改
        assertThat(registry.history().stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }
}
