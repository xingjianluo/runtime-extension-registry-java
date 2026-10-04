package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.VersionKind;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 一键回退语义验证：
 * <ol>
 *   <li>回退成功后对外启用状态与配置与目标版本逐字段一致，生成更新的新编号、原历史不被覆盖；</li>
 *   <li>未受影响的扩展不重复初始化，初始化次数与"成功变更中真正变化的实例数"对得上；</li>
 *   <li>目标含现已不存在的扩展（被移除）、或当前多出目标之后才注册的扩展时整体拒绝，
 *       错误码可与"版本查不到/其它失败"区分，当前状态原样可用；</li>
 *   <li>目标配置在当前环境下无法初始化就绪时整体拒绝，绝不只回退一半；</li>
 *   <li>反复回退、再改回去，最终状态稳定收敛，revertedFrom 记录准确。</li>
 * </ol>
 */
class RollbackBehaviorTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackBehaviorTest.class);

    /**
     * 带静态故障闸门的扩展：闸门打开期间任何一次 initialize 都抛非校验类异常。
     * 用于确定性制造"入库时合法、但回退重建时才无法就绪"。
     */
    static class GatedProbe implements Extension {
        static volatile boolean faultGateOpen = false;

        final AtomicInteger inits;
        final AtomicInteger shutdowns;
        volatile Map<String, Object> activeConfig = Map.of();

        GatedProbe(AtomicInteger inits, AtomicInteger shutdowns) {
            this.inits = inits;
            this.shutdowns = shutdowns;
        }

        @Override
        public void initialize(ExtensionContext context) {
            Object v = context.config().get("v");
            if (!(v instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("v must be a non-blank string");
            }
            if (faultGateOpen) {
                throw new IllegalStateException("runtime gate: cannot become ready now");
            }
            activeConfig = Map.copyOf(context.config());
            inits.incrementAndGet();
        }

        @Override
        public Object execute(Map<String, Object> input) {
            return activeConfig;
        }

        @Override
        public void shutdown() {
            shutdowns.incrementAndGet();
        }
    }

    private final AtomicInteger inits = new AtomicInteger();
    private final AtomicInteger shutdowns = new AtomicInteger();

    private ExtensionDefinition simple(String id, String config, boolean enabled) {
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled,
                Map.of("v", config), () -> new GatedProbe(inits, shutdowns));
    }

    private ChangeSet update(String id, String value) {
        return new ChangeSet(List.of(new ChangeCommand.UpdateConfig(id, Map.of("v", value))));
    }

    private ExtensionStatusView currentView(DefaultExtensionRegistry registry, String id) {
        return registry.snapshot().extensions().stream()
                .filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private Object invokeConfig(DefaultExtensionRegistry registry, String id) {
        return registry.invoke(new ExtensionInvocation(id, Map.of())).data();
    }

    @Test
    void rollbackRestoresStateAppendsNewVersionAndSkipsUnchangedInitializations() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();

        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("a", "a1", true)),
                new ChangeCommand.RegisterDefinition(simple("b", "b1", false)),
                new ChangeCommand.RegisterDefinition(simple("c", "c1", true)))))
                .generation();
        long v2 = registry.apply(new ChangeSet(List.of(
                update("a", "a2").commands().get(0),
                new ChangeCommand.SetEnabled("b", true)))).generation();
        long v3 = registry.apply(update("c", "c3")).generation();
        int initsAfterSetup = inits.get();
        log.info("scenario=rollback-ok versions={},{},{} initsAfterSetup={} (v3 相对 v2 仅 c 变化)",
                v1, v2, v3, initsAfterSetup);

        // 回退到 v2：v3 只改了 c，所以对外唯一变化是 c: c3 -> c1
        long rb = registry.rollback(v2).generation();
        log.info("scenario=rollback-ok rollback->v{} produced v{} initsDelta={}", v2, rb,
                inits.get() - initsAfterSetup);

        assertThat(rb).isGreaterThan(v3);
        HistoryVersion rbv = registry.historyVersion(rb).orElseThrow();
        assertThat(rbv.kind()).isEqualTo(VersionKind.ROLLBACK);
        assertThat(rbv.revertedFrom()).isEqualTo(v2);
        assertThat(rbv.diff().baseVersion()).isEqualTo(v3);
        assertThat(rbv.diff().changes()).extracting(c -> c.extensionId() + ":" + c.type())
                .containsExactly("c:CONFIG_CHANGED");

        // 对外状态逐字段等于 v2
        assertThat(currentView(registry, "a").config()).isEqualTo(Map.of("v", "a2"));
        assertThat(currentView(registry, "a").enabled()).isTrue();
        assertThat(currentView(registry, "b").enabled()).isTrue();
        assertThat(currentView(registry, "c").config()).isEqualTo(Map.of("v", "c1"));

        // 未受影响的 a、b 不重复初始化：本次只有 c 真正变化 -> 恰好新增 1 次初始化
        assertThat(inits.get()).isEqualTo(initsAfterSetup + 1);

        // 原历史不被覆盖
        assertThat(registry.historyVersion(v1)).isPresent();
        assertThat(registry.historyVersion(v2)).isPresent();
        assertThat(registry.historyVersion(v3)).isPresent();

        // 执行基于回退后的世代
        var result = registry.invoke(new ExtensionInvocation("c", Map.of()));
        assertThat(result.generation()).isEqualTo(rb);
        assertThat(result.data()).isEqualTo(Map.of("v", "c1"));
    }

    @Test
    void rollbackToAlreadyCurrentStateAppendsVersionButInitializesNothing() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("a", "a1", true))))).generation();
        long v2 = registry.apply(update("a", "a2")).generation();
        int initsBefore = inits.get();
        long rb1 = registry.rollback(v2).generation();
        assertThat(inits.get()).isEqualTo(initsBefore); // 当前已是 v2 内容
        long rb2 = registry.rollback(v2).generation();
        log.info("scenario=rollback-noop rollback->v{} produced {} then {} initsDelta={} diffSize={}",
                v2, rb1, rb2, inits.get() - initsBefore,
                registry.historyVersion(rb2).orElseThrow().diff().changes().size());
        assertThat(rb2).isGreaterThan(rb1);
        assertThat(inits.get()).isEqualTo(initsBefore);
        assertThat(registry.historyVersion(rb2).orElseThrow().diff().changes()).isEmpty();
    }

    @Test
    void rollbackTargetWithRemovedExtensionIsRejectedAndCurrentStaysUsable() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("a", "a1", true)),
                new ChangeCommand.RegisterDefinition(simple("gone", "g1", true))))).generation();
        long v2 = registry.apply(update("a", "a2")).generation();
        long v3 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RemoveExtension("gone")))).generation();
        log.info("scenario=rollback-removed v1 含 gone，v3 移除，尝试 rollback->v1");

        assertThatThrownBy(() -> registry.rollback(v1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(e -> {
                    ExtensionException ee = (ExtensionException) e;
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_NOT_POSSIBLE);
                    assertThat(ee.code()).isNotEqualTo(ErrorCode.HISTORY_VERSION_NOT_FOUND);
                    assertThat(String.valueOf(ee.context().get("missingInCurrent"))).contains("gone");
                });

        // 当前对外状态原样可用：世代停在 v3、a 仍是 a2、gone 不存在、无额外初始化
        assertThat(registry.snapshot().generation()).isEqualTo(v3);
        assertThat(currentView(registry, "a").config()).isEqualTo(Map.of("v", "a2"));
        assertThat(registry.status("gone")).isEmpty();
        assertThat(invokeConfig(registry, "a")).isEqualTo(Map.of("v", "a2"));
    }

    @Test
    void rollbackTargetMissingAnExtensionRegisteredAfterwardIsRejected() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("a", "a1", true))))).generation();
        long v2 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("late", "l1", true))))).generation();
        log.info("scenario=rollback-added-after 目标 v1 不含 late（v2 才注册），尝试 rollback->v1");

        assertThatThrownBy(() -> registry.rollback(v1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(e -> {
                    ExtensionException ee = (ExtensionException) e;
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_NOT_POSSIBLE);
                    assertThat(String.valueOf(ee.context().get("addedAfterTarget"))).contains("late");
                });

        assertThat(registry.snapshot().generation()).isEqualTo(v2);
        assertThat(registry.status("late")).isPresent();
        assertThat(invokeConfig(registry, "late")).isEqualTo(Map.of("v", "l1"));
    }

    @Test
    void rollbackThatCannotInitializeNowIsRejectedAtomicallyAndSucceedsOnceHealthy() {
        GatedProbe.faultGateOpen = false;
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("a", "a1", true))))).generation();
        long v2 = registry.apply(update("a", "a2")).generation();
        int initsHealthy = inits.get();

        // 打开故障闸门：回退到 v1 需要用 a1 重新初始化 a，此刻无法就绪 -> 整体拒绝
        GatedProbe.faultGateOpen = true;
        log.info("scenario=rollback-cannot-ready gate=OPEN 尝试 rollback->v{}，期望 ROLLBACK_NOT_POSSIBLE", v1);
        assertThatThrownBy(() -> registry.rollback(v1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(e -> {
                    ExtensionException ee = (ExtensionException) e;
                    assertThat(ee.code()).isEqualTo(ErrorCode.ROLLBACK_NOT_POSSIBLE);
                    assertThat(String.valueOf(ee.context().get("reason")))
                            .isEqualTo(ErrorCode.INITIALIZATION_FAILED.name());
                });

        // 绝没有回退一半：世代仍停在 v2、对外仍是 a2、现有实例仍可服务
        assertThat(registry.snapshot().generation()).isEqualTo(v2);
        assertThat(invokeConfig(registry, "a")).isEqualTo(Map.of("v", "a2"));
        // 失败尝试期间新建的实例被丢弃，不计入成功初始化次数
        assertThat(inits.get()).isEqualTo(initsHealthy);

        // 关闭闸门后回退成功，状态收敛到 a1
        GatedProbe.faultGateOpen = false;
        long rb = registry.rollback(v1).generation();
        log.info("scenario=rollback-cannot-ready gate=CLOSED rollback->v{} produced v{} config={}",
                v1, rb, invokeConfig(registry, "a"));
        assertThat(rb).isGreaterThan(v2);
        assertThat(invokeConfig(registry, "a")).isEqualTo(Map.of("v", "a1"));
        assertThat(inits.get()).isEqualTo(initsHealthy + 1);
    }

    @Test
    void repeatedRollbacksAndReEditsConvergeToStableState() {
        GatedProbe.faultGateOpen = false;
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(simple("a", "base", true))))).generation();
        long v2 = registry.apply(update("a", "two")).generation();

        long current = v2;
        for (int round = 1; round <= 5; round++) {
            long rb = registry.rollback(v1).generation();
            log.info("scenario=converge round={} rollback->v1=>v{} config={}", round, rb,
                    currentView(registry, "a").config());
            assertThat(currentView(registry, "a").config()).isEqualTo(Map.of("v", "base"));
            assertThat(invokeConfig(registry, "a")).isEqualTo(Map.of("v", "base"));

            long forward = registry.apply(update("a", "two")).generation();
            log.info("scenario=converge round={} re-edit->two=>v{} config={}", round, forward,
                    currentView(registry, "a").config());
            assertThat(currentView(registry, "a").config()).isEqualTo(Map.of("v", "two"));
            current = forward;
        }
        assertThat(currentView(registry, "a").config()).isEqualTo(Map.of("v", "two"));
        assertThat(registry.snapshot().generation()).isEqualTo(current);
        // 12 个版本 < 默认保留上限 20，回退目标始终可查
        assertThat(registry.historyVersion(v1)).isPresent();
        assertThat(registry.historyVersion(v2)).isPresent();
    }
}
