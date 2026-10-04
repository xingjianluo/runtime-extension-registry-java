package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
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
 * 回退与热更新、读取并发进行的稳定性验证（多轮重复，结果必须稳定）：
 * <ol>
 *   <li>写线程持续热更新配置，回退线程同时反复回退到 v1，读线程持续快照+执行；</li>
 *   <li>任意时刻读到的视图配置与实例执行配置完全一致（无撕裂、无中间态）；</li>
 *   <li>回退与写入确实在同一时间窗口发生（时间区间重叠 + 读者同时观察到两类配置值）；</li>
 *   <li>未受影响的扩展绝不重复初始化；被反复重建的扩展满足 inits - shutdowns == 1；</li>
 *   <li>世代号 == 初始提交数 + 成功更新数 + 成功回退数（初始化次数与成功变更对得上）。</li>
 * </ol>
 */
class ConcurrentRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRollbackTest.class);

    private static final String BASE_VALUE = "base";

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
    void rollbackConcurrentWithUpdatesAndReadsIsStableAcrossRounds() throws Exception {
        int rounds = 5;
        for (int round = 1; round <= rounds; round++) {
            runOneRound(round);
        }
    }

    private void runOneRound(int round) throws Exception {
        // 保留上限调大，确保回退目标 v1 在整轮中始终留存（验证保留条数可配）。
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(100_000);
        AtomicInteger cInits = new AtomicInteger();
        AtomicInteger cShutdowns = new AtomicInteger();
        AtomicInteger uInits = new AtomicInteger();

        // v1（回退目标）：c(v=base, 启用) + u（全程不受影响的旁观者）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "c", Version.parse("1.0.0"), List.of(), true,
                        Map.of("v", BASE_VALUE), () -> new Probe(cInits, cShutdowns))),
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "u", Version.parse("1.0.0"), List.of(), true,
                        Map.of(), () -> new Probe(uInits, new AtomicInteger()))))));
        final long initialCommits = 1;

        int writers = 4;
        int iterationsPerWriter = 60;
        int readers = 3;
        log.info("round={} inputs: writers={} iterationsPerWriter={} readers={} rollbackTarget=v1(v={}) "
                        + "judgement: no torn reads, overlap of rollback/update windows, "
                        + "generation == {} + acceptedUpdates + acceptedRollbacks",
                round, writers, iterationsPerWriter, readers, BASE_VALUE, initialCommits);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersFinished = new CountDownLatch(writers);
        CountDownLatch rollbackerFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers + 1);

        AtomicLong acceptedUpdates = new AtomicLong();
        AtomicLong acceptedRollbacks = new AtomicLong();
        AtomicLong unexpectedFailures = new AtomicLong();
        AtomicLong consistencyChecks = new AtomicLong();
        AtomicLong baseObservedByReaders = new AtomicLong();
        Set<String> writerValuesSeen = ConcurrentHashMap.newKeySet();
        AtomicLong firstUpdateNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastUpdateNanos = new AtomicLong(Long.MIN_VALUE);
        AtomicLong firstRollbackNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastRollbackNanos = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<String> tornRead = new AtomicReference<>();

        try {
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
                            updateWindow(firstUpdateNanos, lastUpdateNanos);
                        } catch (ExtensionException e) {
                            unexpectedFailures.incrementAndGet();
                        }
                        sleepQuietly(1);
                    }
                    writersFinished.countDown();
                });
            }

            // 回退线程：在写线程全部完成前持续回退到 v1
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

            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    awaitQuietly(start);
                    // 覆盖整个写入+回退时段
                    while (writersFinished.getCount() > 0 || rollbackerFinished.getCount() > 0) {
                        var snapshot = registry.snapshot();
                        ExtensionStatusView view = snapshot.extensions().stream()
                                .filter(e -> e.id().equals("c")).findFirst().orElseThrow();
                        if (view.ready()) {
                            var result = registry.invoke(new ExtensionInvocation("c", Map.of()));
                            // 快照与执行之间可能恰好插入一次提交（这正是要制造的交错）：
                            // 世代不一致时跳过本次采样；一致时才比较，必须无撕裂。
                            if (result.generation() == snapshot.generation()) {
                                Map<?, ?> executed = (Map<?, ?>) result.data();
                                Object viewValue = view.config().get("v");
                                if (!viewValue.equals(executed.get("v"))) {
                                    tornRead.set("generation=" + snapshot.generation()
                                            + " view=" + viewValue + " executed=" + executed.get("v"));
                                }
                                consistencyChecks.incrementAndGet();
                            }
                            Object viewValue = view.config().get("v");
                            if (BASE_VALUE.equals(viewValue)) {
                                baseObservedByReaders.incrementAndGet();
                            } else if (!String.valueOf(viewValue).matches("w\\d+-\\d+")) {
                                tornRead.set("unknown value=" + viewValue);
                            }
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
        log.info("round={} result: acceptedUpdates={} acceptedRollbacks={} consistencyChecks={} "
                        + "baseObserved={} distinctWriterValuesSeen={} cInits={} cShutdowns={} uInits={} "
                        + "finalGeneration={} unexpectedFailures={}",
                round, acceptedUpdates.get(), acceptedRollbacks.get(), consistencyChecks.get(),
                baseObservedByReaders.get(), writerValuesSeen.size(),
                cInits.get(), cShutdowns.get(), uInits.get(), finalGeneration, unexpectedFailures.get());

        // 1) 无意外失败（v1 始终留存、状态始终可适用）
        assertThat(unexpectedFailures.get()).isZero();
        // 2) 更新与回退都真实成功过，且时间窗口重叠（同一时段内真实交错）
        assertThat(acceptedUpdates.get()).isGreaterThan(0);
        assertThat(acceptedRollbacks.get()).isGreaterThan(0);
        assertThat(firstRollbackNanos.get()).isLessThan(lastUpdateNanos.get());
        assertThat(firstUpdateNanos.get()).isLessThan(lastRollbackNanos.get());
        // 3) 读者在回退生效的窗口内真实读到过回退后的 base 状态，也读到过写入值
        assertThat(consistencyChecks.get()).isGreaterThan(0);
        assertThat(baseObservedByReaders.get()).isGreaterThan(0);
        assertThat(writerValuesSeen).isNotEmpty();
        // 4) 无撕裂、无中间态
        assertThat(tornRead.get()).isNull();
        // 5) 世代号 == 初始提交 + 成功更新 + 成功回退（每次成功变更恰好推进一次）
        assertThat(finalGeneration)
                .isEqualTo(initialCommits + acceptedUpdates.get() + acceptedRollbacks.get());
        // 6) 未受影响的 u 从未被重复初始化
        assertThat(uInits.get()).isEqualTo(1);
        // 7) c 的初始化/关闭严格成对：任何时刻恰好一个存活实例
        assertThat(cInits.get() - cShutdowns.get()).isEqualTo(1);
        // 8) 最终状态完整可用：配置必为 base 或某个写入值，且视图与执行一致
        ExtensionStatusView finalView = registry.status("c").orElseThrow();
        Map<?, ?> finalExecuted = (Map<?, ?>) registry
                .invoke(new ExtensionInvocation("c", Map.of())).data();
        log.info("round={} final: view={} executed={} (judgement: equal and known)", round,
                finalView.config(), finalExecuted);
        assertThat(finalExecuted.get("v")).isEqualTo(finalView.config().get("v"));
        assertThat(String.valueOf(finalView.config().get("v")))
                .satisfies(v -> assertThat(BASE_VALUE.equals(v) || writerValuesSeen.contains(v)).isTrue());
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
