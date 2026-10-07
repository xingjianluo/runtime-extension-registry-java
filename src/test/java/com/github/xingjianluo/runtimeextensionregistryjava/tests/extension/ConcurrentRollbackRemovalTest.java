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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "回退会移除目标版本之后注册的扩展"与热更新/读取并发交错的稳定性验证（多轮重复）：
 * <ol>
 *   <li>写线程持续热更新 base 配置，注册线程反复注册 extra，回退线程同时反复回退到 v1
 *       （每次成功回退都会把 extra 移除、把 base 配置重置），读线程持续快照+执行；</li>
 *   <li>任意时刻读到的要么是完整旧状态、要么是完整新状态：base 的视图配置与实例执行
 *       在同一世代内必须一致，extra 在快照中"出现/消失"是原子的；</li>
 *   <li>三类操作确实在同一时间窗口真实交错（时间区间重叠 + 读者两类状态都观察到）；</li>
 *   <li>旁观者 u 绝不重复初始化；世代号与成功变更次数精确对账；
 *       extra 的初始化/关闭严格成对；</li>
 *   <li>并发结束后再次回退到 v1，对外状态稳定收敛到与 v1 逐项一致。</li>
 * </ol>
 */
class ConcurrentRollbackRemovalTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRollbackRemovalTest.class);

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
    void rollbackRemovingLaterRegistrationsIsStableUnderConcurrency() throws Exception {
        int rounds = 5;
        for (int round = 1; round <= rounds; round++) {
            runOneRound(round);
        }
    }

    private void runOneRound(int round) throws Exception {
        // 保留上限调大，确保回退目标 v1 在整轮中始终留存。
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(100_000);
        AtomicInteger baseInits = new AtomicInteger();
        AtomicInteger baseShutdowns = new AtomicInteger();
        AtomicInteger uInits = new AtomicInteger();
        AtomicInteger extraInits = new AtomicInteger();
        AtomicInteger extraShutdowns = new AtomicInteger();

        // v1（回退目标）：base(v=base, 启用) + u（全程不受影响的旁观者）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "base", Version.parse("1.0.0"), List.of(), true,
                        Map.of("v", BASE_VALUE), () -> new Probe(baseInits, baseShutdowns))),
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "u", Version.parse("1.0.0"), List.of(), true,
                        Map.of(), () -> new Probe(uInits, new AtomicInteger()))))));
        // v2：目标版本之后注册 extra（回退 v1 时它会被移除）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "extra", Version.parse("1.0.0"), List.of(), true,
                        Map.of(), () -> new Probe(extraInits, extraShutdowns))))));
        final long initialCommits = 2;

        int writers = 3;
        int iterationsPerWriter = 50;
        int readers = 3;
        log.info("round={} inputs: writers={} iterationsPerWriter={} readers={} rollbackTarget=v1 "
                        + "(base only, extra must be removed by each successful rollback) "
                        + "judgement: no torn reads, real overlap, generation == {} + accepted ops, "
                        + "u never re-initialized, extra init/shutdown paired",
                round, writers, iterationsPerWriter, readers, initialCommits);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersFinished = new CountDownLatch(writers);
        CountDownLatch workersFinished = new CountDownLatch(2); // 注册线程 + 回退线程
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers + 2);

        AtomicLong acceptedUpdates = new AtomicLong();
        AtomicLong acceptedRegisters = new AtomicLong();
        AtomicLong acceptedRollbacks = new AtomicLong();
        AtomicLong duplicateRegistrations = new AtomicLong();
        AtomicLong unexpectedFailures = new AtomicLong();
        AtomicLong consistencyChecks = new AtomicLong();
        AtomicLong baseValueObserved = new AtomicLong();
        AtomicLong extraSeenPresent = new AtomicLong();
        AtomicLong extraSeenAbsent = new AtomicLong();
        Set<String> writerValuesSeen = ConcurrentHashMap.newKeySet();
        AtomicLong firstWriteNanos = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lastWriteNanos = new AtomicLong(Long.MIN_VALUE);
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
                                    new ChangeCommand.UpdateConfig("base", Map.of("v", value)))));
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

            // 注册线程：extra 被回退移除后负责把它注册回来（已存在则属预期重复）
            pool.submit(() -> {
                awaitQuietly(start);
                while (writersFinished.getCount() > 0) {
                    try {
                        registry.apply(new ChangeSet(List.of(
                                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                                        "extra", Version.parse("1.0.0"), List.of(), true,
                                        Map.of(), () -> new Probe(extraInits, extraShutdowns))))));
                        acceptedRegisters.incrementAndGet();
                        updateWindow(firstWriteNanos, lastWriteNanos);
                    } catch (ExtensionException e) {
                        if (e.code() == ErrorCode.DUPLICATE_EXTENSION_ID) {
                            duplicateRegistrations.incrementAndGet();
                        } else {
                            unexpectedFailures.incrementAndGet();
                        }
                    }
                    sleepQuietly(1);
                }
                workersFinished.countDown();
            });

            // 回退线程：持续回退到 v1，每次成功都会移除 extra 并重置 base 配置
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
                workersFinished.countDown();
            });

            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    awaitQuietly(start);
                    while (writersFinished.getCount() > 0 || workersFinished.getCount() > 0) {
                        RegistrySnapshot snapshot = registry.snapshot();
                        Map<String, ExtensionStatusView> views = snapshot.extensions().stream()
                                .collect(Collectors.toMap(ExtensionStatusView::id, v -> v));
                        // base 必须始终存在且就绪（v1 与任何后续状态都包含它）
                        ExtensionStatusView base = views.get("base");
                        if (base == null || !base.ready()) {
                            tornRead.set("base missing or not ready in generation "
                                    + snapshot.generation());
                            continue;
                        }
                        var result = registry.invoke(new ExtensionInvocation("base", Map.of()));
                        // 快照与执行之间可能恰好插入一次提交（这正是要制造的交错）：
                        // 世代一致时才比较，必须无撕裂。
                        if (result.generation() == snapshot.generation()) {
                            Map<?, ?> executed = (Map<?, ?>) result.data();
                            if (!base.config().get("v").equals(executed.get("v"))) {
                                tornRead.set("generation=" + snapshot.generation()
                                        + " view=" + base.config().get("v")
                                        + " executed=" + executed.get("v"));
                            }
                            consistencyChecks.incrementAndGet();
                        }
                        Object value = base.config().get("v");
                        if (BASE_VALUE.equals(value)) {
                            baseValueObserved.incrementAndGet();
                        } else if (!String.valueOf(value).matches("w\\d+-\\d+")) {
                            tornRead.set("unknown base value=" + value);
                        }
                        // extra 的出现/消失必须是原子的：要么完整在快照里且就绪，要么完全不在
                        ExtensionStatusView extra = views.get("extra");
                        if (extra == null) {
                            extraSeenAbsent.incrementAndGet();
                        } else {
                            extraSeenPresent.incrementAndGet();
                            if (!extra.ready()) {
                                tornRead.set("extra present but not ready in generation "
                                        + snapshot.generation());
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

        long generationAfterStorm = registry.snapshot().generation();
        log.info("round={} result: acceptedUpdates={} acceptedRegisters={} acceptedRollbacks={} "
                        + "duplicates={} consistencyChecks={} baseValueObserved={} "
                        + "extraPresent={} extraAbsent={} generationAfterStorm={} "
                        + "unexpectedFailures={}",
                round, acceptedUpdates.get(), acceptedRegisters.get(), acceptedRollbacks.get(),
                duplicateRegistrations.get(), consistencyChecks.get(), baseValueObserved.get(),
                extraSeenPresent.get(), extraSeenAbsent.get(), generationAfterStorm,
                unexpectedFailures.get());

        // 1) 无意外失败（v1 始终留存、base 始终存在，回退与更新永远可适用）
        assertThat(unexpectedFailures.get()).isZero();
        // 2) 三类操作都真实成功过，且写与回退的时间窗口重叠（同一时段真实交错）
        assertThat(acceptedUpdates.get()).isGreaterThan(0);
        assertThat(acceptedRegisters.get()).isGreaterThan(0);
        assertThat(acceptedRollbacks.get()).isGreaterThan(0);
        assertThat(firstRollbackNanos.get()).isLessThan(lastWriteNanos.get());
        assertThat(firstWriteNanos.get()).isLessThan(lastRollbackNanos.get());
        // 3) 读者真实观察到两类状态：extra 存在过也消失过、base 值与写入值都出现过
        assertThat(consistencyChecks.get()).isGreaterThan(0);
        assertThat(extraSeenPresent.get()).isGreaterThan(0);
        assertThat(extraSeenAbsent.get()).isGreaterThan(0);
        assertThat(baseValueObserved.get()).isGreaterThan(0);
        assertThat(writerValuesSeen).isNotEmpty();
        // 4) 无撕裂、无中间态
        assertThat(tornRead.get()).isNull();
        // 5) 世代号 == 初始提交 + 成功更新 + 成功注册 + 成功回退（每次成功变更恰好推进一次）
        assertThat(generationAfterStorm).isEqualTo(initialCommits
                + acceptedUpdates.get() + acceptedRegisters.get() + acceptedRollbacks.get());
        // 6) 旁观者 u 从未被重复初始化
        assertThat(uInits.get()).isEqualTo(1);
        // 7) extra 每次成功注册恰好初始化一次；任何时刻至多一个存活实例
        assertThat(extraInits.get()).isEqualTo(acceptedRegisters.get() + 1); // +1 为 v2 的初始注册
        assertThat(extraInits.get() - extraShutdowns.get())
                .isIn(0, 1); // 取决于风暴停止时 extra 是否已被回退移除
        // 8) base 的初始化/关闭严格成对：任何时刻恰好一个存活实例
        assertThat(baseInits.get() - baseShutdowns.get()).isEqualTo(1);

        // 9) 风暴停止后再回退到 v1：对外状态必须稳定收敛到与 v1 逐项一致
        registry.rollback(1);
        RegistrySnapshot finalSnapshot = registry.snapshot();
        Map<String, ExtensionStatusView> finalViews = finalSnapshot.extensions().stream()
                .collect(Collectors.toMap(ExtensionStatusView::id, v -> v));
        log.info("round={} final: visible={} base.config={} (judgement: exactly {base,u}, v={})",
                round, finalViews.keySet(), finalViews.get("base").config(), BASE_VALUE);
        assertThat(finalViews.keySet()).containsExactlyInAnyOrder("base", "u");
        assertThat(finalViews.get("base").config()).isEqualTo(Map.of("v", BASE_VALUE));
        assertThat(finalViews.get("base").enabled()).isTrue();
        assertThat(registry.status("extra")).isEmpty();
        // 收敛后 extra 的初始化/关闭完全成对，不再有存活实例
        assertThat(extraInits.get()).isEqualTo(extraShutdowns.get());
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
