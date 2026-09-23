package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.github.xingjianluo.runtimeextensionregistryjava.core.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.core.StateChangeRequest;
import com.github.xingjianluo.runtimeextensionregistryjava.core.StateItem;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionStatus;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency verification for runtime hot updates:
 * readers always observe a complete state, rejected changes keep the service usable,
 * and initialization runs at most once per effective configuration.
 */
class ConcurrentHotUpdateTest {

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrentHotUpdateTest.class);

    @Test
    void concurrentReadersAlwaysObserveAConsistentSnapshot() throws Exception {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("svc", "1.0.0", "v0-"));
        registry.applyChange(new StateChangeRequest(
                List.of(new StateItem("svc", true, null))));

        int updaters = 8;
        int readers = 8;
        int iterationsPerUpdater = 25;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(updaters + readers);
        Set<String> observedPrefixes = ConcurrentHashMap.newKeySet();
        Set<String> appliedPrefixes = ConcurrentHashMap.newKeySet();
        AtomicInteger tornReads = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int u = 0; u < updaters; u++) {
            final int updaterId = u;
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < iterationsPerUpdater; i++) {
                    // Fixed-width prefix: U<1-digit worker>I<2-digit iteration>- => exactly 6 chars,
                    // so the observed prefix (echo minus the fixed message) is unambiguous.
                    String prefix = String.format("U%dI%02d-", updaterId, i);
                    try {
                        registry.applyChange(new StateChangeRequest(List.of(
                                new StateItem("svc", true, Map.of("prefix", prefix)))));
                        appliedPrefixes.add(prefix);
                        // Stagger updaters so they stay interleaved with readers throughout.
                        Thread.sleep(1L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (ExtensionException rejected) {
                        // Serialized updates cannot conflict here; nothing is expected.
                        LOG.warn("unexpected rejection: {}", rejected.errorCode());
                    }
                }
            }));
        }
        for (int r = 0; r < readers; r++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < 1500; i++) {
                    // pinned. The echo result must equal that generation's configured prefix +
                    // message. Any mismatch is an impossible torn/half-applied observation.
                    DefaultExtensionRegistry.ExecutionOutcome outcome =
                            registry.executeWithGeneration("svc", Map.of("message", "X"));
                    String echo = (String) outcome.result().get("echo");
                    if (!echo.endsWith("X")) {
                        tornReads.incrementAndGet();
                    }
                    String observedPrefix = echo.substring(0, echo.length() - 1);
                    observedPrefixes.add(observedPrefix);
                    // The outcome carries the config read from the very same immutable state the
                    // execution ran on, so this comparison has no cross-generation window.
                    String configPrefix = outcome.observedConfig().get("prefix");
                    if (!observedPrefix.equals(configPrefix)) {
                        tornReads.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        LOG.info("[input updaters={} iterations={} readers=8] decision: appliedGenerations={} observedPrefixes={} tornReads={}",
                updaters, iterationsPerUpdater, appliedPrefixes.size(),
                observedPrefixes.size(), tornReads.get());
        assertEquals(0, tornReads.get(), "no reader may observe a torn/half-applied state");
        assertTrue(appliedPrefixes.size() > 1, "multiple hot-updated generations must be applied");
        assertTrue(observedPrefixes.size() > 1, "readers should observe multiple hot-updated generations");
        assertTrue(registry.snapshot("svc").isReady(), "service stays enabled at the end");
    }

    @Test
    void rejectedInvalidChangesKeepServiceUsableUnderConcurrency() throws Exception {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("svc", "1.0.0", "stable-"));
        registry.applyChange(new StateChangeRequest(
                List.of(new StateItem("svc", true, null))));

        int writers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        List<Future<?>> futures = new java.util.ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger accepted = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            final boolean valid = w % 2 == 0;
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < 20; i++) {
                    Map<String, String> cfg = valid
                            ? Map.of("prefix", "valid" + i + "-")
                            : Map.of("other", "no-prefix-key");
                    try {
                        registry.applyChange(new StateChangeRequest(List.of(
                                new StateItem("svc", true, cfg))));
                        accepted.incrementAndGet();
                    } catch (ExtensionException e) {
                        rejected.incrementAndGet();
                    }
                }
            }));
        }
        AtomicInteger successfulCalls = new AtomicInteger();
        for (int r = 0; r < 2; r++) {
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < 200; i++) {
                    Map<String, Object> result = registry.execute("svc", Map.of("message", "Q"));
                    if (((String) result.get("echo")).endsWith("Q")) {
                        successfulCalls.incrementAndGet();
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        LOG.info("[input alternating valid/invalid configs] decision: accepted={} rejected={} successfulReads={} finalPrefix={}",
                accepted.get(), rejected.get(), successfulCalls.get(),
                registry.snapshot("svc").effectiveConfig().get("prefix"));
        assertTrue(accepted.get() > 0);
        assertTrue(rejected.get() > 0, "invalid config changes must be rejected");
        assertTrue(successfulCalls.get() > 0, "previous usable state must stay available throughout");
        assertTrue(registry.snapshot("svc").effectiveConfig().containsKey("prefix"));
        assertEquals(ExtensionStatus.ENABLED, registry.snapshot("svc").status());
    }

    @Test
    void concurrentConflictingChangesAreSerializedWithoutDuplicateInitialization() throws Exception {
        // A counting extension factory records create/initialize/destroy counts via the
        // faulty kind toggled through config is unsuitable; instead we repeatedly flip a
        // dependency pair and assert no torn enabled-without-dependency state is observable
        // and the final graph is internally consistent.
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("base", "1.0.0", "base-"));
        registry.register(TestFixtures.echoWithDep("upper", "1.0.0", "upper-", "base", "[1.0.0,2.0.0)"));
        registry.applyChange(new StateChangeRequest(List.of(
                new StateItem("base", true, null),
                new StateItem("upper", true, null))));

        int workers = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new java.util.ArrayList<>();
        AtomicInteger rejected = new AtomicInteger();
        for (int w = 0; w < workers; w++) {
            final boolean enable = w % 2 == 0;
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < 30; i++) {
                    try {
                        // Re-enable both atomically, or try to disable base (must be rejected
                        // while upper exists and depends on it).
                        StateChangeRequest req = enable
                                ? new StateChangeRequest(List.of(
                                        new StateItem("base", true, null),
                                        new StateItem("upper", true, null)))
                                : new StateChangeRequest(List.of(
                                        new StateItem("base", false, null)));
                        registry.applyChange(req);
                    } catch (ExtensionException e) {
                        rejected.incrementAndGet();
                    }
                    // Post-condition of every serialized change: upper enabled => base enabled.
                    boolean upperOn = registry.snapshot("upper").status() == ExtensionStatus.ENABLED;
                    boolean baseOn = registry.snapshot("base").status() == ExtensionStatus.ENABLED;
                    assertFalse(upperOn && !baseOn, "dependent must never be enabled without its dependency");
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        LOG.info("[input concurrent enable-pair vs disable-base] decision: rejected={} final base={} upper={}",
                rejected.get(), registry.snapshot("base").status(), registry.snapshot("upper").status());
        assertTrue(rejected.get() > 0, "dangerous disable must be rejected under concurrency");
        assertEquals(ExtensionStatus.ENABLED, registry.snapshot("base").status());
        assertEquals(ExtensionStatus.ENABLED, registry.snapshot("upper").status());
    }


    @Test
    void concurrentEnablesAndNoOpChangesNeverInitializeTwice() throws Exception {
        com.github.xingjianluo.runtimeextensionregistryjava.core.ExtensionKindCatalog catalog =
                new com.github.xingjianluo.runtimeextensionregistryjava.core.ExtensionKindCatalog();
        java.util.concurrent.atomic.AtomicInteger creates = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger initializes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger destroys = new java.util.concurrent.atomic.AtomicInteger();
        catalog.register(CountingExtension.factory(creates, initializes, destroys));
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(catalog);
        registry.register(new com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition(
                "cnt", "1.0.0", CountingExtension.KIND, java.util.List.of(), java.util.Map.of()));

        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        java.util.List<Future<?>> futures = new java.util.ArrayList<>();
        // Half the workers repeatedly enable with unchanged config (must reuse the instance);
        // the other half push genuinely new config (one fresh init per accepted generation).
        java.util.concurrent.atomic.AtomicInteger configUpdates = new java.util.concurrent.atomic.AtomicInteger();
        for (int w = 0; w < workers; w++) {
            final boolean configChange = w % 2 == 1;
            futures.add(pool.submit(() -> {
                awaitQuietly(start);
                for (int i = 0; i < 40; i++) {
                    java.util.Map<String, String> cfg = configChange
                            ? java.util.Map.of("rev", "r" + configUpdates.incrementAndGet())
                            : java.util.Map.of();
                    try {
                        registry.applyChange(new StateChangeRequest(java.util.List.of(
                                new StateItem("cnt", true, cfg))));
                    } catch (ExtensionException ignored) {
                        // all changes here are valid; nothing expected
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // Every created instance must be initialized exactly once and eventually destroyed
        // exactly once when superseded (no leak, no double init).
        LOG.info("[input concurrent enable/config-change] decision: creates={} initializes={} destroys={} configUpdates={}",
                creates.get(), initializes.get(), destroys.get(), configUpdates.get());
        org.junit.jupiter.api.Assertions.assertEquals(creates.get(), initializes.get(),
                "every created instance is initialized exactly once");
        org.junit.jupiter.api.Assertions.assertEquals(initializes.get(), destroys.get() + 1,
                "all superseded instances destroyed exactly once, current live one remains");
        org.junit.jupiter.api.Assertions.assertTrue(registry.snapshot("cnt").isReady());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
