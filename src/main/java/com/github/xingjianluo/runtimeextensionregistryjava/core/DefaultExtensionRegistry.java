package com.github.xingjianluo.runtimeextensionregistryjava.core;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.model.DependencySpec;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionSnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.model.ExtensionStatus;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.spi.ExtensionRuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Default registry implementation.
 *
 * <p>Concurrency model:
 * <ul>
 *   <li>All state lives in an immutable {@link RegistryState}, published through a
 *       {@code volatile} reference. Readers (snapshot/execute) always observe either
 *       the complete old state or the complete new state - never a torn mix.</li>
 *   <li>All mutations run under a single write lock, so changes are serialized and
 *       initialization never runs twice for the same effective configuration.</li>
 *   <li>A change builds the whole new world (validated configs + fresh instances)
 *       before publication. Any failure destroys provisional instances and leaves
 *       the previous state fully available (atomic rollback).</li>
 *   <li>Execution failures are caught per call and normalized; they never mutate
 *       registry state nor affect other extensions.</li>
 * </ul>
 */
public final class DefaultExtensionRegistry implements ExtensionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultExtensionRegistry.class);

    private final ExtensionKindCatalog catalog;
    private final Object writeLock = new Object();
    private long generationSeq = 0;
    private volatile RegistryState state = new RegistryState(0, Map.of());

    public DefaultExtensionRegistry(ExtensionKindCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public long currentGeneration() {
        return state.generation();
    }

    @Override
    public void register(ExtensionDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (writeLock) {
            RegistryState current = state;
            if (current.snapshots.containsKey(definition.id())) {
                throw new ExtensionException(ExtensionErrorCode.DUPLICATE_EXTENSION_ID,
                        "extension id already registered: " + definition.id(),
                        Map.of("extensionId", definition.id()));
            }
            ExtensionFactory factory = catalog.require(definition.kind());
            validateConfig(definition, factory);
            ExtensionSnapshot snapshot = new ExtensionSnapshot(
                    definition, ExtensionStatus.DISABLED, null, definition.config());
            Map<String, ExtensionSnapshot> next = new LinkedHashMap<>(current.snapshots);
            next.put(definition.id(), snapshot);
            state = new RegistryState(++generationSeq, Collections.unmodifiableMap(next));
            LOG.info("extension registered id={} version={} kind={} configKeys={}",
                    definition.id(), definition.version(), definition.kind(),
                    definition.config().keySet());
        }
    }

    @Override
    public ExtensionSnapshot snapshot(String extensionId) {
        RegistryState current = state;
        ExtensionSnapshot snapshot = current.snapshots.get(extensionId);
        if (snapshot == null) {
            throw new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION,
                    "unknown extension: " + extensionId, Map.of("extensionId", extensionId));
        }
        return snapshot;
    }

    @Override
    public Map<String, ExtensionSnapshot> snapshots() {
        return state.snapshots;
    }

    @Override
    public void applyChange(StateChangeRequest request) {
        Objects.requireNonNull(request, "request");
        synchronized (writeLock) {
            applyChangeLocked(request);
        }
    }

    private void applyChangeLocked(StateChangeRequest request) {
        RegistryState current = state;

        // ---- 1. Resolve requested targets, rejecting malformed/ambiguous items. ----
        Set<String> touched = new HashSet<>();
        Map<String, Boolean> targetEnabled = new HashMap<>();
        Map<String, Map<String, String>> targetConfig = new HashMap<>();
        for (StateItem item : request.items()) {
            if (!touched.add(item.extensionId())) {
                throw new ExtensionException(ExtensionErrorCode.CHANGE_SET_REJECTED,
                        "extension appears more than once in the same change set: " + item.extensionId(),
                        Map.of("extensionId", item.extensionId()));
            }
            ExtensionSnapshot existing = current.snapshots.get(item.extensionId());
            if (existing == null) {
                throw new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION,
                        "change targets unknown extension: " + item.extensionId(),
                        Map.of("extensionId", item.extensionId()));
            }
            boolean enabled = item.enabled() != null
                    ? item.enabled()
                    : existing.status() == ExtensionStatus.ENABLED;
            Map<String, String> config = item.config() != null
                    ? Map.copyOf(item.config())
                    : existing.effectiveConfig();
            targetEnabled.put(item.extensionId(), enabled);
            targetConfig.put(item.extensionId(), config);
        }

        // ---- 2. Build the hypothetical post-change world. ----
        Map<String, ExtensionDefinition> targetDefinitions = new HashMap<>();
        Set<String> enabledSet = new HashSet<>();
        for (ExtensionSnapshot s : current.snapshots.values()) {
            String id = s.definition().id();
            boolean enabled = targetEnabled.getOrDefault(id, s.status() == ExtensionStatus.ENABLED);
            Map<String, String> cfg = targetConfig.getOrDefault(id, s.effectiveConfig());
            ExtensionDefinition def = s.definition();
            targetDefinitions.put(id, new ExtensionDefinition(id, def.version().toString(),
                    def.kind(), def.dependencies(), cfg));
            if (enabled) {
                enabledSet.add(id);
            }
        }

        // ---- 3. Validate config of every extension receiving a config update. ----
        for (String id : touched) {
            ExtensionDefinition def = targetDefinitions.get(id);
            validateConfig(def, catalog.require(def.kind()));
        }

        // ---- 4. Validate the whole enabled graph in the target world. This covers
        //        extensions not touched by the change whose readiness may be broken
        //        indirectly (e.g. their sole dependency gets disabled). ----
        for (String id : enabledSet) {
            DependencyResolver.validate(id, targetDefinitions, enabledSet::contains);
        }
        List<String> initOrder = DependencyResolver.topologicalOrder(enabledSet, targetDefinitions);

        // ---- 5. Create + initialize the required new instances (dependencies first). ----
        Map<String, Extension> instances = new HashMap<>();
        List<Extension> freshInstances = new ArrayList<>();
        for (String id : initOrder) {
            ExtensionSnapshot old = current.snapshots.get(id);
            Map<String, String> cfg = targetDefinitions.get(id).config();
            boolean canReuse = old.status() == ExtensionStatus.ENABLED
                    && old.instance() != null
                    && old.effectiveConfig().equals(cfg);
            if (canReuse) {
                instances.put(id, old.instance());
                continue;
            }
            ExtensionDefinition def = targetDefinitions.get(id);
            Extension extension;
            try {
                extension = catalog.require(def.kind()).create(def);
            } catch (RuntimeException e) {
                rollbackFresh(freshInstances);
                throw normalizedInitFailure(def, e);
            }
            ExtensionRuntimeContext ctx = new DefaultRuntimeContext(id, cfg, depId ->
                    Optional.ofNullable(instances.get(depId)));
            try {
                extension.initialize(ctx);
            } catch (Exception e) {
                rollbackFresh(append(freshInstances, extension));
                throw normalizedInitFailure(def, e);
            }
            freshInstances.add(extension);
            instances.put(id, extension);
        }

        // ---- 6. Assemble and atomically publish the next immutable state. ----
        Map<String, ExtensionSnapshot> next = new LinkedHashMap<>();
        for (String id : current.snapshots.keySet()) {
            ExtensionSnapshot old = current.snapshots.get(id);
            ExtensionDefinition def = targetDefinitions.get(id);
            if (enabledSet.contains(id)) {
                Extension instance = instances.get(id);
                next.put(id, new ExtensionSnapshot(def, ExtensionStatus.ENABLED, instance, def.config()));
            } else {
                next.put(id, new ExtensionSnapshot(def, ExtensionStatus.DISABLED, null, def.config()));
            }
        }
        state = new RegistryState(++generationSeq, Collections.unmodifiableMap(next));

        // ---- 7. Retire superseded/disposed instances after publication. ----
        List<Extension> retired = new ArrayList<>();
        for (String id : current.snapshots.keySet()) {
            ExtensionSnapshot old = current.snapshots.get(id);
            Extension oldInstance = old.instance();
            if (oldInstance == null) {
                continue;
            }
            boolean stillSame = enabledSet.contains(id)
                    && instances.get(id) == oldInstance;
            if (!stillSame) {
                retired.add(oldInstance);
            }
        }
        Collections.reverse(retired);
        for (Extension extension : retired) {
            safeDestroy(extension);
        }

        LOG.info("change set applied size={} enabledAfter={} freshlyInitialized={}",
                request.items().size(), enabledSet, freshInstances.size());
    }

    @Override
    public Map<String, Object> execute(String extensionId, Map<String, Object> input) {
        return executeWithGeneration(extensionId, input).result();
    }

    /** Like {@link #execute} but also returns the exact state generation the call pinned. */
    public ExecutionOutcome executeWithGeneration(String extensionId, Map<String, Object> input) {
        RegistryState current = state;
        ExtensionSnapshot snapshot = current.snapshots.get(extensionId);
        if (snapshot == null) {
            throw new ExtensionException(ExtensionErrorCode.UNKNOWN_EXTENSION,
                    "unknown extension: " + extensionId, Map.of("extensionId", extensionId));
        }
        if (!snapshot.isReady()) {
            throw new ExtensionException(ExtensionErrorCode.EXTENSION_NOT_READY,
                    "extension is not enabled: " + extensionId,
                    Map.of("extensionId", extensionId, "status", snapshot.status().name()));
        }
        // Defensive: never execute when a dependency is not ready in the observed state.
        for (DependencySpec dep : snapshot.definition().dependencies()) {
            ExtensionSnapshot depSnapshot = current.snapshots.get(dep.extensionId());
            if (depSnapshot == null || !depSnapshot.isReady()) {
                throw new ExtensionException(ExtensionErrorCode.DEPENDENCY_NOT_READY,
                        "dependency not ready, request not executed",
                        Map.of("extensionId", extensionId, "dependencyId", dep.extensionId()));
            }
        }
        ExtensionRuntimeContext context = new DefaultRuntimeContext(
                extensionId, snapshot.effectiveConfig(),
                depId -> Optional.ofNullable(current.snapshots.get(depId))
                        .filter(ExtensionSnapshot::isReady)
                        .map(ExtensionSnapshot::instance));
        try {
            Map<String, Object> result = snapshot.instance().execute(
                    input == null ? Map.of() : input, context);
            return new ExecutionOutcome(current.generation(), snapshot.effectiveConfig(), result);
        } catch (ExtensionException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("extension execution failed id={} kind={}", extensionId,
                    snapshot.definition().kind(), e);
            throw new ExtensionException(ExtensionErrorCode.EXECUTION_FAILED,
                    "extension execution failed",
                    Map.of("extensionId", extensionId, "kind", snapshot.definition().kind()), e);
        }
    }

    /**
     * Result of an execution tagged with the exact immutable state generation it ran
     * against and the effective config observed within that same generation.
     */
    public record ExecutionOutcome(long generation, Map<String, String> observedConfig,
                                   Map<String, Object> result) {
    }

    // ------------------------------------------------------------------ helpers

    private void validateConfig(ExtensionDefinition definition, ExtensionFactory factory) {
        try {
            factory.validateConfig(definition);
        } catch (ExtensionException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("invalid config id={} kind={} configKeys={}", definition.id(),
                    definition.kind(), definition.config().keySet(), e);
            throw new ExtensionException(ExtensionErrorCode.INVALID_CONFIG,
                    "invalid configuration for extension: " + definition.id(),
                    Map.of("extensionId", definition.id(), "kind", definition.kind(),
                            "configKeys", new ArrayList<>(definition.config().keySet())), e);
        }
    }

    private ExtensionException normalizedInitFailure(ExtensionDefinition def, Exception e) {
        LOG.warn("initialization failed id={} kind={}", def.id(), def.kind(), e);
        return new ExtensionException(ExtensionErrorCode.INITIALIZATION_FAILED,
                "extension initialization failed, change rolled back: " + def.id(),
                Map.of("extensionId", def.id(), "kind", def.kind(),
                        "configKeys", new ArrayList<>(def.config().keySet())), e);
    }

    private static List<Extension> append(List<Extension> list, Extension extension) {
        List<Extension> copy = new ArrayList<>(list);
        copy.add(extension);
        return copy;
    }

    private static void rollbackFresh(List<Extension> freshInstances) {
        List<Extension> reverse = new ArrayList<>(freshInstances);
        Collections.reverse(reverse);
        for (Extension extension : reverse) {
            safeDestroy(extension);
        }
    }

    private static void safeDestroy(Extension extension) {
        try {
            extension.destroy();
        } catch (RuntimeException e) {
            LOG.warn("extension destroy() failed; ignored to preserve isolation", e);
        }
    }

    /** Immutable registry state. */
    private record RegistryState(long generation, Map<String, ExtensionSnapshot> snapshots) {
        RegistryState {
            snapshots = snapshots == null ? Map.of() : Map.copyOf(snapshots);
        }
    }

    /** Default runtime context, resolving dependencies from a fixed state view. */
    private record DefaultRuntimeContext(
            String extensionId,
            Map<String, String> config,
            java.util.function.Function<String, Optional<Extension>> resolver)
            implements ExtensionRuntimeContext {

        private DefaultRuntimeContext {
            config = Map.copyOf(config);
        }

        @Override
        public Optional<Extension> dependency(String dependencyId) {
            return resolver.apply(dependencyId);
        }
    }
}
