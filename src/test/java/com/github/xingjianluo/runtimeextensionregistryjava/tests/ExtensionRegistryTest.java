package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.github.xingjianluo.runtimeextensionregistryjava.core.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.core.StateChangeRequest;
import com.github.xingjianluo.runtimeextensionregistryjava.core.StateItem;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionStatus;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionRegistryTest {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionRegistryTest.class);

    private void registerTwoEchos(DefaultExtensionRegistry registry) {
        registry.register(TestFixtures.echo("base", "1.0.0", "base-"));
        registry.register(TestFixtures.echoWithDep("upper", "1.0.0", "upper-", "base", "[1.0.0,2.0.0)"));
    }

    @Test
    void duplicateIdIsRejectedAndNeverOverwrites() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("dup", "1.0.0", "first-"));
        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> registry.register(TestFixtures.echo("dup", "2.0.0", "second-")));
        LOG.info("[input second registration id=dup v2 prefix=second-] decision: rejected code={}",
                ex.errorCode());
        assertEquals(ExtensionErrorCode.DUPLICATE_EXTENSION_ID, ex.errorCode());
        assertEquals("1.0.0", registry.snapshot("dup").definition().version().toString());
        assertEquals("first-", registry.snapshot("dup").effectiveConfig().get("prefix"));
    }

    @Test
    void unknownKindIsRejectedAtRegistration() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        ExtensionDefinition unknown = new ExtensionDefinition("x", "1.0.0", "nope",
                List.of(), Map.of());
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.register(unknown));
        LOG.info("[input kind=nope] decision: rejected code={}", ex.errorCode());
        assertEquals(ExtensionErrorCode.UNKNOWN_EXTENSION_KIND, ex.errorCode());
    }

    @Test
    void invalidConfigIsRejectedAtRegistration() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        ExtensionDefinition bad = new ExtensionDefinition("bad", "1.0.0", "echo",
                List.of(), Map.of());
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.register(bad));
        LOG.info("[input id=bad kind=echo config={}] decision: rejected code={} context={}",
                bad.config(), ex.errorCode(), ex.context());
        assertEquals(ExtensionErrorCode.INVALID_CONFIG, ex.errorCode());
    }

    @Test
    void enablingExtensionWithMissingDependencyIsRejected() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echoWithDep("a", "1.0.0", "a-", "ghost", "[1.0.0,2.0.0)"));
        StateChangeRequest request = new StateChangeRequest(
                List.of(new StateItem("a", true, null)));
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.applyChange(request));
        LOG.info("[input change enable=a, dep ghost missing] decision: rejected code={} context={}",
                ex.errorCode(), ex.context());
        assertEquals(ExtensionErrorCode.DEPENDENCY_NOT_FOUND, ex.errorCode());
        assertEquals(ExtensionStatus.DISABLED, registry.snapshot("a").status());
    }

    @Test
    void incompatibleDependencyVersionIsRejected() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("base", "1.5.0", "base-"));
        registry.register(TestFixtures.echoWithDep("upper", "1.0.0", "upper-", "base", "[2.0.0,3.0.0)"));
        StateChangeRequest request = new StateChangeRequest(
                List.of(new StateItem("upper", true, null)));
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.applyChange(request));
        LOG.info("[input enable=upper requires base[2.0.0,3.0.0) base=1.5.0] decision: rejected code={}",
                ex.errorCode());
        assertEquals(ExtensionErrorCode.VERSION_INCOMPATIBLE, ex.errorCode());
        assertEquals(ExtensionStatus.DISABLED, registry.snapshot("upper").status());
    }

    @Test
    void dependencyNotReadyIsRejectedUntilDependencyEnabled() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("base", "1.0.0", "base-"));
        registry.register(TestFixtures.echoWithDep("upper", "1.0.0", "upper-", "base", "[1.0.0,2.0.0)"));

        StateChangeRequest upperOnly = new StateChangeRequest(
                List.of(new StateItem("upper", true, null)));
        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> registry.applyChange(upperOnly));
        LOG.info("[input enable=upper only, base disabled] decision: rejected code={}", ex.errorCode());
        assertEquals(ExtensionErrorCode.DEPENDENCY_NOT_READY, ex.errorCode());

        registry.applyChange(new StateChangeRequest(List.of(
                new StateItem("base", true, null),
                new StateItem("upper", true, null))));
        LOG.info("[input enable={base,upper} together] decision: accepted statuses=ENABLED,ENABLED");
        assertEquals(ExtensionStatus.ENABLED, registry.snapshot("base").status());
        assertEquals(ExtensionStatus.ENABLED, registry.snapshot("upper").status());
    }

    @Test
    void disablingDependencyRejectsDependentsButKeepsPreviousStateAvailable() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registerTwoEchos(registry);
        registry.applyChange(new StateChangeRequest(List.of(
                new StateItem("base", true, null),
                new StateItem("upper", true, null))));
        Map<String, Object> before = registry.execute("upper", Map.of("message", "x"));

        StateChangeRequest bad = new StateChangeRequest(
                List.of(new StateItem("base", false, null)));
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.applyChange(bad));
        LOG.info("[input disable=base while upper enabled] decision: rejected code={}, previous state kept",
                ex.errorCode());
        assertEquals(ExtensionErrorCode.DEPENDENCY_NOT_READY, ex.errorCode());
        assertEquals(ExtensionStatus.ENABLED, registry.snapshot("base").status());
        assertEquals(before, registry.execute("upper", Map.of("message", "x")));
    }

    @Test
    void initializationFailureRollsBackWholeBatch() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("good", "1.0.0", "good-"));
        registry.register(TestFixtures.faulty("bad", "true", "false"));

        StateChangeRequest request = new StateChangeRequest(List.of(
                new StateItem("good", true, null),
                new StateItem("bad", true, null)));
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.applyChange(request));
        LOG.info("[input enable={good,bad}, bad fails init] decision: rejected code={}; all stay DISABLED",
                ex.errorCode());
        assertEquals(ExtensionErrorCode.INITIALIZATION_FAILED, ex.errorCode());
        assertEquals(ExtensionStatus.DISABLED, registry.snapshot("good").status());
        assertEquals(ExtensionStatus.DISABLED, registry.snapshot("bad").status());
    }

    @Test
    void invalidConfigChangeIsRejectedAndPreviousStateStaysUsable() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("e", "1.0.0", "old-"));
        registry.applyChange(new StateChangeRequest(
                List.of(new StateItem("e", true, null))));
        Object oldInstance = registry.snapshot("e").instance();

        StateChangeRequest bad = new StateChangeRequest(List.of(
                new StateItem("e", null, Map.of())));
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.applyChange(bad));
        LOG.info("[input config={} for enabled echo (prefix missing)] decision: rejected code={}",
                Map.of(), ex.errorCode());
        assertEquals(ExtensionErrorCode.INVALID_CONFIG, ex.errorCode());
        assertEquals("old-", registry.snapshot("e").effectiveConfig().get("prefix"));
        assertEquals(oldInstance, registry.snapshot("e").instance());
        assertEquals(Map.of("echo", "old-hello"),
                registry.execute("e", Map.of("message", "hello")));
    }

    @Test
    void validConfigHotUpdateReinitializesAtomically() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("e", "1.0.0", "old-"));
        registry.applyChange(new StateChangeRequest(
                List.of(new StateItem("e", true, null))));
        Object oldInstance = registry.snapshot("e").instance();

        registry.applyChange(new StateChangeRequest(List.of(
                new StateItem("e", true, Map.of("prefix", "new-")))));
        LOG.info("[input config prefix old- -> new-] decision: accepted, new instance initialized");
        assertEquals("new-", registry.snapshot("e").effectiveConfig().get("prefix"));
        assertNotEquals(oldInstance, registry.snapshot("e").instance());
        assertEquals(Map.of("echo", "new-hello"),
                registry.execute("e", Map.of("message", "hello")));
    }

    @Test
    void executionFailureIsNormalizedAndIsolatedFromOtherExtensions() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.faulty("flaky", "false", "true"));
        registry.register(TestFixtures.echo("sibling", "1.0.0", "ok-"));
        registry.applyChange(new StateChangeRequest(List.of(
                new StateItem("flaky", true, null),
                new StateItem("sibling", true, null))));

        ExtensionException ex = assertThrows(ExtensionException.class,
                () -> registry.execute("flaky", Map.of()));
        LOG.info("[input execute=flaky failOnExecute=true] decision: normalized code={} (raw fault hidden)",
                ex.errorCode());
        assertEquals(ExtensionErrorCode.EXECUTION_FAILED, ex.errorCode());
        assertFalse(ex.getMessage().contains("simulated"));
        assertEquals(Map.of("echo", "ok-1"),
                registry.execute("sibling", Map.of("message", "1")));
    }

    @Test
    void disabledOrUnknownExtensionExecutionIsRejected() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("e", "1.0.0", "p-"));

        ExtensionException notReady = assertThrows(ExtensionException.class,
                () -> registry.execute("e", Map.of()));
        LOG.info("[input execute=e disabled] decision: rejected code={}", notReady.errorCode());
        assertEquals(ExtensionErrorCode.EXTENSION_NOT_READY, notReady.errorCode());

        ExtensionException unknown = assertThrows(ExtensionException.class,
                () -> registry.execute("nope", Map.of()));
        assertEquals(ExtensionErrorCode.UNKNOWN_EXTENSION, unknown.errorCode());
        assertTrue(unknown.context().containsKey("extensionId"));
    }

    @Test
    void duplicateItemInSameChangeSetIsRejected() {
        DefaultExtensionRegistry registry = TestFixtures.newRegistry();
        registry.register(TestFixtures.echo("e", "1.0.0", "p-"));
        StateChangeRequest request = new StateChangeRequest(List.of(
                new StateItem("e", true, null),
                new StateItem("e", false, null)));
        ExtensionException ex = assertThrows(ExtensionException.class, () -> registry.applyChange(request));
        LOG.info("[input same extension twice in one batch] decision: rejected code={}", ex.errorCode());
        assertEquals(ExtensionErrorCode.CHANGE_SET_REJECTED, ex.errorCode());
    }
}
