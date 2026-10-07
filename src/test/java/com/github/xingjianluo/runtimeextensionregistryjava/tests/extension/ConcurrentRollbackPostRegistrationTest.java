package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发稳定性：回退目标版本之后又有新扩展注册的场景下，
 * 回退（移除额外扩展 x）、热更新（改 c 的配置）、重新注册 x、持续读取 在同一时间窗口交错。
 * <ol>
 *   <li>任意时刻读到的要么是完整旧状态、要么是完整新状态：c 的视图与执行同世代一致；
 *       x 要么完整可见（启用、配置即注册值）、要么完全不存在，绝不存在"半个 x"；</li>
 *   <li>回退/更新/重注册确实发生在同一时间窗口（时间区间重叠 + 读者同时观察到
 *       x 存在与不存在、base 与写入值）；</li>
 *   <li>世代号 == 初始提交 + 成功更新 + 成功回退 + 成功重注册（可对账）；</li>
 *   <li>旁观者 u 绝不重复初始化；c、x 的初始化/关闭严格成对（存活实例数 ∈ {0,1}）；</li>
 *   <li>多轮重复结果稳定，最终状态完整可用。</li>
 * </ol>
 */
class ConcurrentRollbackPostRegistrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRollbackPostRegistrationTest.class);

    private static final String BASE_VALUE = "base";
    private static final String X_CONFIG_VALUE = "x1";

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

    @Test
    void rollbackDroppingExtrasConcurrentWithUpdatesAndReadsIsStableAcrossRounds() throws Exception {
        int rounds = 5;
        for (int round = 1; round <= rounds; round++) {
            runOneRound(round);
        }
    }

    private void runOneRound(int round) throws Exception {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(100_000);
        AtomicInteger cInits = new AtomicInteger();
        AtomicInteger cShutdowns = new AtomicInteger();
        AtomicInteger uInits = new AtomicInteger();
        AtomicInteger xInits = new AtomicInteger();
        AtomicInteger xShutdowns = new AtomicInteger();

        // v1（回退目标）：c(v=base, 启用) + u（全程不受影响的旁观者）；x 此时不存在
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "c", Version.parse("1.0.0"), List.of(), true,
                        Map.of("v", BASE_VALUE), () -> new Probe(cInits, cShutdowns))),
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "u", Version.parse("1.0.0"), List.of(), true,
                        Map.of(), () -> new Probe(uInits, new AtomicInteger()))))));
        final long initialCommits = 1;

        int writers = 3;
        int iterationsPerWriter = 40;
        int readers = 3;
        log.info("round={} inputs: writers={} iterationsPerWriter={} readers={} rollbackTarget=v1(v={}) "
                        + "extraExtension=x(k={}) judgement: no torn/partial reads, x either fully visible "
                        + "or fully absent, windows overlap, generation == {} + updates + rollbacks + re-registers",
                round, writers, iterationsPerWriter, readers, BASE_VALUE, X_CONFIG_VALUE, initialCommits);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersFinished = new CountDownLatch(writers);
        CountDownLatch rollbackerFinished = new CountDownLatch(1);
        CountDownLatch registrarFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers + 2);

        AtomicLong acceptedUpdates = new AtomicLong();
        AtomicLong acceptedRollbacks = new AtomicLong();
        AtomicLong acceptedRegisters = new AtomicLong();
        AtomicLong unexpectedFailures = new AtomicLong();
        AtomicLong consistencyChecks = new AtomicLong();
        AtomicLong baseObserved = new AtomicLong();
        AtomicLong xSeenPresent = new AtomicLong();
        AtomicLong xSeenAbsent = new AtomicLong();
        Set<String> writerValuesSeen = ConcurrentHashMap.newKeySet();
        AtomicLong firstWriteNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastWriteNanos = new AtomicLong(Long.MIN_VALUE);
        AtomicLong firstRollbackNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastRollbackNanos = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<String> tornRead = new AtomicReference<>();

        try {
            // 写线程：持续热更新 c 的配置
            for (int w = 0; w < writers; w++) {
                final int writerId = w;
                pool.submit(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < iterationsPerWriter; i++) {
                        String value = "w" + writerId + "-" + i;
                        try {
                            registry.apply(new ChangeSet(List.of(
                                    new ChangeCommand.UpdateConfig("c", Map.of("v", value)))));
                            acceptedUpdates.incrementAndGet();
                            writerValuesSeen.add(value);
                            updateWindow(firstWriteNanos, lastWriteNanos);
                        } catch (ExtensionException e) {
                            unexpectedFailures.incrementAndGet();
                        }
                        sleepQuietly(1);
                    }
                    writersFinished.countDown();
                });
            }

            // 回退线程：写线程完成前持续回退到 v1（会把额外扩展 x 移出对外状态）
            pool.submit(() -> {
                awaitQuietly(start);
                while (writersFinished.getCount() > 0) {
                    try {
                        registry.rollback(1);
                        acceptedRollbacks.incrementAndGet();
                        updateWindow(firstRollbackNanos, lastRollbackNanos);
                    } catch (ExtensionException e) {
                        unexpectedFailures.incrementAndGet();
                    }
                    sleepQuietly(1);
                }
                rollbackerFinished.countDown();
            });

            // 重注册线程：发现 x 被回退移除后就重新注册，制造"额外扩展"反复进出
            pool.submit(() -> {
                awaitQuietly(start);
                while (writersFinished.getCount() > 0) {
                    if (registry.status("x").isEmpty()) {
                        try {
                            registry.apply(new ChangeSet(List.of(
                                    new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                                            "x", Version.parse("1.0.0"), List.of(), true,
                                            Map.of("k", X_CONFIG_VALUE),
                                            () -> new Probe(xInits, xShutdowns))))));
                            acceptedRegisters.incrementAndGet();
                        } catch (ExtensionException e) {
                            // 与回退交错时唯一可接受的冲突是并发重复注册；单注册线程下不应发生
                            unexpectedFailures.incrementAndGet();
                        }
                    }
                    sleepQuietly(1);
                }
                registrarFinished.countDown();
            });

            // 读线程：持续快照 + 执行，校验无撕裂、x 无"半个"状态
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    awaitQuietly(start);
                    while (writersFinished.getCount() > 0
                            || rollbackerFinished.getCount() > 0
                            || registrarFinished.getCount() > 0) {
                        RegistrySnapshot snapshot = registry.snapshot();
                        // c 必须始终完整可见且同世代内视图与执行一致
                        ExtensionStatusView cView = snapshot.extensions().stream()
                                .filter(e -> e.id().equals("c")).findFirst().orElse(null);
                        if (cView == null) {
                            tornRead.set("c missing from snapshot generation=" + snapshot.generation());
                        } else if (cView.ready()) {
                            try {
                                var result = registry.invoke(new ExtensionInvocation("c", Map.of()));
                                if (result.generation() == snapshot.generation()) {
                                    Map<?, ?> executed = (Map<?, ?>) result.data();
                                    if (!cView.config().get("v").equals(executed.get("v"))) {
                                        tornRead.set("c torn: generation=" + snapshot.generation()
                                                + " view=" + cView.config().get("v")
                                                + " executed=" + executed.get("v"));
                                    }
                                    consistencyChecks.incrementAndGet();
                                }
                            } catch (ExtensionException e) {
                                tornRead.set("c invoke failed while visible+ready: " + e.code());
                            }
                            Object v = cView.config().get("v");
                            if (BASE_VALUE.equals(v)) {
                                baseObserved.incrementAndGet();
                            } else if (!String.valueOf(v).matches("w\\d+-\\d+")) {
                                tornRead.set("c unknown value=" + v);
                            }
                        }
                        // x 要么完整可见（启用 + 配置即注册值），要么完全不存在
                        Optional<ExtensionStatusView> xView = snapshot.extensions().stream()
                                .filter(e -> e.id().equals("x")).findFirst();
                        if (xView.isPresent()) {
                            xSeenPresent.incrementAndGet();
                            ExtensionStatusView x = xView.get();
                            if (!x.enabled() || !X_CONFIG_VALUE.equals(x.config().get("k"))) {
                                tornRead.set("x partially visible: enabled=" + x.enabled()
                                        + " config=" + x.config());
                            }
                        } else {
                            xSeenAbsent.incrementAndGet();
                        }
                        sleepQuietly(1);
                    }
                });
            }

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long finalGeneration = registry.snapshot().generation();
        boolean xFinallyPresent = registry.status("x").isPresent();
        log.info("round={} result: acceptedUpdates={} acceptedRollbacks={} acceptedRegisters={} "
                        + "consistencyChecks={} baseObserved={} xPresent={} xAbsent={} "
                        + "cInits={} cShutdowns={} uInits={} xInits={} xShutdowns={} "
                        + "finalGeneration={} xFinallyPresent={} unexpectedFailures={}",
                round, acceptedUpdates.get(), acceptedRollbacks.get(), acceptedRegisters.get(),
                consistencyChecks.get(), baseObserved.get(), xSeenPresent.get(), xSeenAbsent.get(),
                cInits.get(), cShutdowns.get(), uInits.get(), xInits.get(), xShutdowns.get(),
                finalGeneration, xFinallyPresent, unexpectedFailures.get());

        // 1) 无意外失败（v1 始终留存可回退；c/u 始终注册；单注册线程不会重复注册 x）
        assertThat(unexpectedFailures.get()).isZero();
        // 2) 三类变更都真实成功过，且回退与写入窗口重叠（同一时间窗口真实交错）
        assertThat(acceptedUpdates.get()).isGreaterThan(0);
        assertThat(acceptedRollbacks.get()).isGreaterThan(0);
        assertThat(acceptedRegisters.get()).isGreaterThan(0);
        assertThat(firstRollbackNanos.get()).isLessThan(lastWriteNanos.get());
        assertThat(firstWriteNanos.get()).isLessThan(lastRollbackNanos.get());
        // 3) 读者真实观察到两类状态：回退后的 base、写入值；x 存在与不存在都出现过
        assertThat(consistencyChecks.get()).isGreaterThan(0);
        assertThat(baseObserved.get()).isGreaterThan(0);
        assertThat(writerValuesSeen).isNotEmpty();
        assertThat(xSeenPresent.get()).isGreaterThan(0);
        assertThat(xSeenAbsent.get()).isGreaterThan(0);
        // 4) 无撕裂、无"半个 x"
        assertThat(tornRead.get()).isNull();
        // 5) 世代号精确对账：每次成功变更恰好推进一次
        assertThat(finalGeneration).isEqualTo(initialCommits
                + acceptedUpdates.get() + acceptedRollbacks.get() + acceptedRegisters.get());
        // 6) 未受影响的 u 从未重复初始化
        assertThat(uInits.get()).isEqualTo(1);
        // 7) c 始终恰好一个存活实例；x 的存活数与最终可见性一致
        assertThat(cInits.get() - cShutdowns.get()).isEqualTo(1);
        assertThat(xInits.get()).isEqualTo(acceptedRegisters.get());
        assertThat(xInits.get() - xShutdowns.get()).isEqualTo(xFinallyPresent ? 1 : 0);
        // 8) 最终状态完整可用：c 视图与执行一致；x 若可见则配置正确、可执行
        ExtensionStatusView finalC = registry.status("c").orElseThrow();
        Map<?, ?> finalExecuted = (Map<?, ?>) registry
                .invoke(new ExtensionInvocation("c", Map.of())).data();
        log.info("round={} final: cView={} cExecuted={} xPresent={} (judgement: c consistent, "
                + "x consistent with its visibility)", round, finalC.config(), finalExecuted, xFinallyPresent);
        assertThat(finalExecuted.get("v")).isEqualTo(finalC.config().get("v"));
        if (xFinallyPresent) {
            ExtensionStatusView x = registry.status("x").orElseThrow();
            assertThat(x.enabled()).isTrue();
            assertThat(x.config()).isEqualTo(Map.of("k", X_CONFIG_VALUE));
            Map<?, ?> xData = (Map<?, ?>) registry.invoke(new ExtensionInvocation("x", Map.of())).data();
            assertThat(xData.get("k")).isEqualTo(X_CONFIG_VALUE);
        }
    }

    private static void updateWindow(AtomicLong first, AtomicLong last) {
        long now = System.nanoTime();
        first.accumulateAndGet(now, Math::min);
        last.accumulateAndGet(now, Math::max);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
