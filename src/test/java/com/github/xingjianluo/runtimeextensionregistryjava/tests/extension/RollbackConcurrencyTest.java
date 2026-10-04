package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import org.junit.jupiter.api.RepeatedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回退与热更新/读取并发时的一致性验证（{@link RepeatedTest} 多轮，结果必须稳定）。
 *
 * <p>包含两段证据：
 * <ol>
 *   <li><b>受控交错</b>：用初始化闸门把一次热更新确定性地卡在 initialize 中，在该窗口内启动回退线程与读
 *       线程，用发生序计数证明读/写/回退确实在同一时间窗口真实重叠，且读者绝不会看到半生效的 "slow"。</li>
 *   <li><b>逐轮屏障压力</b>：每轮所有更新线程、回退线程、读线程在同一个 {@link CyclicBarrier} 集合后
 *       同时发起操作，因此每一轮都确定性地发生真实交错（而非依赖调度的"假并行"）；读者每次读取都校验
 *       "快照视图配置 == 实例执行返回配置"，最终再用历史 diff 精确对账初始化次数。</li>
 * </ol>
 */
class RollbackConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(RollbackConcurrencyTest.class);

    /** 可被静态闸门阻塞在 initialize 内的探针；记录进入初始化的发生序。 */
    static class SlowProbe implements Extension {
        static volatile InitGate gate;
        static final AtomicInteger initCount = new AtomicInteger();
        static final AtomicInteger shutdownCount = new AtomicInteger();
        /** 发生序：1=有初始化已进入，2=回退线程已请求，3=窗口内读取已完成。 */
        static final AtomicInteger interleaveOrder = new AtomicInteger();
        /** 曾成功生效的配置值（用于断言最终值来源合法）。 */
        static final java.util.Set<String> committedValues = ConcurrentHashMap.newKeySet();
        volatile Map<String, Object> activeConfig = Map.of("v", "v0");

        @Override
        public void initialize(ExtensionContext context) {
            Object v = context.config().get("v");
            if (!(v instanceof String value) || value.startsWith("bad")) {
                throw new IllegalArgumentException("v must be a good string");
            }
            InitGate g = gate;
            if (g != null && g.matchValue.equals(value)) {
                interleaveOrder.compareAndSet(0, 1);
                g.entered.countDown();
                try {
                    assertThat(g.release.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            activeConfig = Map.copyOf(context.config());
            initCount.incrementAndGet();
            committedValues.add(value);
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

    record InitGate(String matchValue, CountDownLatch entered, CountDownLatch release) {
    }

    private static ChangeSet cfg(String value) {
        return new ChangeSet(List.of(new ChangeCommand.UpdateConfig("c", Map.of("v", value))));
    }

    @RepeatedTest(30)
    void rollbackConcurrentWithUpdatesAndReadsNeverTears() throws Exception {
        SlowProbe.initCount.set(0);
        SlowProbe.shutdownCount.set(0);
        SlowProbe.interleaveOrder.set(0);
        SlowProbe.committedValues.clear();

        // 保留上限设大，保证回退锚点在整个用例内不被淘汰（淘汰由 HistoryAndEvictionTest 覆盖）。
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(100_000);
        ExtensionDefinition def = new ExtensionDefinition(
                "c", Version.parse("1.0.0"), List.of(), true,
                Map.of("v", "v0"), SlowProbe::new);
        registry.apply(new ChangeSet(List.of(new ChangeCommand.RegisterDefinition(def))));
        long anchor1 = registry.apply(cfg("v1")).generation();
        long anchor2 = registry.apply(cfg("v2")).generation();
        SlowProbe.committedValues.add("v1");
        SlowProbe.committedValues.add("v2");

        // ---------- 阶段 A：受控交错，证明读/写/回退真实同屏 ----------
        controlledInterleave(registry, anchor1);
        final int initsAfterControlled = SlowProbe.initCount.get();

        // ---------- 阶段 B：逐轮屏障压力 ----------
        StressResult r = barrierStress(registry, anchor1, anchor2);

        long finalGeneration = registry.snapshot().generation();
        Object finalValue = registry.invoke(new ExtensionInvocation("c", Map.of())).data();
        String finalV = String.valueOf(((Map<?, ?>) finalValue).get("v"));

        // 精确对账（顺序无关）：扫描压力阶段每个历史版本，diff 中 c 变化即对应一次实例重建。
        int rebuiltFromHistory = 0;
        for (long g = r.stressStartGeneration() + 1; g <= finalGeneration; g++) {
            HistoryVersion hv = registry.historyVersion(g).orElseThrow();
            boolean cChanged = hv.diff().changes().stream().anyMatch(c -> c.extensionId().equals("c"));
            if (cChanged) {
                rebuiltFromHistory++;
            }
        }
        log.info("scenario=reconcile rounds={} accepted={} rejected={} rollbacksOk={} checks={} "
                        + "generationsSeen={} finalGen={} finalValue={} initsAfterControlled={} rebuilt={} "
                        + "totalInits={} shutdowns={}",
                r.rounds(), r.accepted(), r.rejected(), r.rollbacksOk(), r.checks(),
                r.generationsSeen(), finalGeneration, finalV, initsAfterControlled, rebuiltFromHistory,
                SlowProbe.initCount.get(), SlowProbe.shutdownCount.get());

        assertThat(r.accepted()).isGreaterThan(0);
        assertThat(r.rejected()).isGreaterThan(0);
        assertThat(r.rollbacksOk()).isGreaterThan(0);
        assertThat(r.rollbacksRejected()).isZero();
        assertThat(r.unexpectedRejectionCodes()).isZero();
        // 任何一次同屏读取都不得观察到半生效/撕裂状态。
        assertThat(r.tearObservations()).as("no torn reads allowed under concurrency").isZero();
        // 每轮每个读者都与提交流程同屏读取一次，检查数确定等于 rounds*readers（屏障保证，不依赖调度）。
        assertThat(r.checks()).isEqualTo(r.rounds() * r.readers());
        assertThat(r.generationsSeen()).isGreaterThan(1);
        assertThat(finalV).doesNotStartWith("bad");
        assertThat(SlowProbe.committedValues.contains(finalV) || finalV.equals("v1") || finalV.equals("v2"))
                .as("final value %s must be a previously committed value", finalV).isTrue();
        // 生命周期严格配对：关闭数 == 初始化数 - 当前唯一存活实例(1)。
        assertThat(SlowProbe.shutdownCount.get()).isEqualTo(SlowProbe.initCount.get() - 1);
        // 初始化次数精确等于：受控阶段累计 + 压力阶段真正引起重建的提交数（未受影响不重复初始化）。
        assertThat(SlowProbe.initCount.get())
                .as("inits must equal initsAfterControlled + rebuiltCommits")
                .isEqualTo(initsAfterControlled + rebuiltFromHistory);
    }

    /** 阶段 A：把热更新卡在初始化内，与回退、读取真实重叠。 */
    private void controlledInterleave(DefaultExtensionRegistry registry, long rollbackTarget) throws Exception {
        InitGate gate = new InitGate("slow", new CountDownLatch(1), new CountDownLatch(1));
        SlowProbe.gate = gate;

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch updateReturned = new CountDownLatch(1);
        CountDownLatch rollbackDone = new CountDownLatch(1);
        CountDownLatch readDuringWindowDone = new CountDownLatch(1);

        // 写线程：提交会卡在 initialize 的热更新
        pool.submit(() -> {
            try {
                registry.apply(cfg("slow"));
            } catch (Exception ignored) {
                // 与回退竞争，两种先后都合法
            } finally {
                updateReturned.countDown();
            }
        });
        assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();

        // 回退线程：此刻写线程占着写锁，回退必须阻塞等待 -> 写/回退真实重叠
        pool.submit(() -> {
            SlowProbe.interleaveOrder.compareAndSet(1, 2);
            try {
                registry.rollback(rollbackTarget);
            } catch (ExtensionException e) {
                log.warn("rollback in controlled window rejected: {}", e.code());
            } finally {
                rollbackDone.countDown();
            }
        });

        // 读线程：在写线程仍卡初始化（未发布）期间读取，必须只见完整旧状态、绝不见 "slow"
        pool.submit(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && gate.release.getCount() > 0) {
                var snapshot = registry.snapshot();
                ExtensionStatusView view = snapshot.extensions().get(0);
                Object executed = registry.invoke(new ExtensionInvocation("c", Map.of())).data();
                assertThat(view.config()).isEqualTo(executed);
                assertThat(String.valueOf(((Map<?, ?>) executed).get("v"))).isNotEqualTo("slow");
                if (SlowProbe.interleaveOrder.get() >= 2) {
                    SlowProbe.interleaveOrder.set(3);
                    readDuringWindowDone.countDown();
                    return;
                }
            }
        });

        assertThat(readDuringWindowDone.await(10, TimeUnit.SECONDS)).isTrue();
        long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (SlowProbe.interleaveOrder.get() < 3 && System.nanoTime() < waitDeadline) {
            Thread.sleep(2);
        }
        assertThat(SlowProbe.interleaveOrder.get()).isEqualTo(3);
        log.info("scenario=controlled-interleave order=3 (init-entered -> rollback-waiting -> read-done), "
                + "读者在写未发布期间未观察到 slow，读/写/回退确认重叠");

        gate.release.countDown();
        assertThat(updateReturned.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(rollbackDone.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        SlowProbe.gate = null;
    }

    /** 阶段 B 的不可变结果包。 */
    private record StressResult(
            int rounds, int readers, long stressStartGeneration,
            int accepted, int rejected, int rollbacksOk, int rollbacksRejected,
            int checks, int generationsSeen,
            int tearObservations, int unexpectedRejectionCodes) {
    }

    /** 阶段 B：逐轮屏障，更新/回退/读取同屏发起。 */
    private StressResult barrierStress(DefaultExtensionRegistry registry, long anchor1, long anchor2)
            throws Exception {
        int updaters = 4;
        int readers = 2;
        int rounds = 150;
        int participants = updaters + 1 + readers;
        ExecutorService pool = Executors.newFixedThreadPool(participants);
        CyclicBarrier barrier = new CyclicBarrier(participants);
        CountDownLatch done = new CountDownLatch(participants);

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger rollbacksOk = new AtomicInteger();
        AtomicInteger rollbacksRejected = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        // 工作线程只记录"观测事实"，绝不就地断言（断言失败会令线程退出、屏障永久等待）。
        AtomicInteger tearObservations = new AtomicInteger();
        AtomicInteger unexpectedRejectionCodes = new AtomicInteger();
        Map<Long, Boolean> generationsSeen = new ConcurrentHashMap<>();
        long stressStartGeneration = registry.snapshot().generation();
        long[] targets = {anchor1, anchor2};

        for (int u = 0; u < updaters; u++) {
            final int uid = u;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        boolean good = (i & 1) == 0;
                        String value = good ? ("u" + uid + "-" + i) : "bad-" + i;
                        try {
                            registry.apply(cfg(value));
                            accepted.incrementAndGet();
                        } catch (ExtensionException e) {
                            rejected.incrementAndGet();
                            if (e.code() != ErrorCode.INVALID_CONFIG
                                    && e.code() != ErrorCode.INITIALIZATION_FAILED) {
                                unexpectedRejectionCodes.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        pool.submit(() -> {
            try {
                for (int i = 0; i < rounds; i++) {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        registry.rollback(targets[i & 1]);
                        rollbacksOk.incrementAndGet();
                    } catch (ExtensionException e) {
                        rollbacksRejected.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                done.countDown();
            }
        });
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        barrier.await(10, TimeUnit.SECONDS);
                        readOnceConsistent(registry, generationsSeen, checks, tearObservations);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        return new StressResult(rounds, readers, stressStartGeneration,
                accepted.get(), rejected.get(), rollbacksOk.get(), rollbacksRejected.get(),
                checks.get(), generationsSeen.size(), tearObservations.get(),
                unexpectedRejectionCodes.get());
    }

    /**
     * 读一次并记录一致性观测。无论 ready 与否都计一次"同屏读取"（证明并发真实发生）。
     *
     * <p>无撕裂的正确判定不能跨 {@code snapshot()} 与 {@code invoke()} 两次调用来比世代
     * （两次调用之间允许有提交发布）。单次 {@code invoke} 自身固定读取一个已发布世代 g，
     * 因此用该次返回的世代号 g 去取对应历史不可变快照，校验其 c 配置与执行返回数据逐字段一致——
     * 这才是"单次读取内部一致、无半生效"的确定性判据。不一致只计数，由主线程统一断言。
     */
    private static void readOnceConsistent(DefaultExtensionRegistry registry,
                                           Map<Long, Boolean> generationsSeen,
                                           AtomicInteger checks,
                                           AtomicInteger tearObservations) {
        var snapshot = registry.snapshot();
        generationsSeen.put(snapshot.generation(), true);
        ExtensionStatusView currentView = snapshot.extensions().get(0);
        if (!currentView.ready()) {
            checks.incrementAndGet();
            return;
        }
        var result = registry.invoke(new ExtensionInvocation("c", Map.of()));
        long g = result.generation();
        Map<String, Object> configAtGeneration = registry.historyVersion(g).orElseThrow()
                .snapshot().extensions().stream()
                .filter(e -> e.id().equals("c")).findFirst().orElseThrow()
                .config();
        if (!java.util.Objects.equals(result.data(), configAtGeneration)) {
            tearObservations.incrementAndGet();
        }
        checks.incrementAndGet();
    }
}
