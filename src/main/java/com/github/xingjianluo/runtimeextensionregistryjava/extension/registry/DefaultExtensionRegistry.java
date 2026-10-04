package com.github.xingjianluo.runtimeextensionregistryjava.extension.registry;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionChange;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStateSnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@link ExtensionRegistry} 的默认实现。
 *
 * <p>一致性模型：
 * <ul>
 *   <li>当前生效状态保存在一个 {@code volatile} 不可变 {@link Generation} 中；
 *       读取与执行永远基于某一已发布世代，不会读到半生效状态。</li>
 *   <li>变更在调用方持锁的隔离区域内完成：构建候选定义、依赖校验、按拓扑序初始化；
 *       任何一步失败都丢弃中间产物（关闭本次新建实例），已发布世代原封不动，对外保持可用。</li>
 *   <li>全部成功后仅做一次引用赋值完成原子生效，随后才关闭被替换/停用/移除的旧实例；
 *       未受变更影响的实例被原样复用，不重复初始化。</li>
 * </ul>
 */
@Component
public class DefaultExtensionRegistry implements ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionRegistry.class);

    /** 一个已生效世代：全部条目不可变。 */
    private record Entry(ExtensionDefinition definition, Extension instance, boolean enabled, boolean ready) {
    }

    private record Generation(long number, Map<String, Entry> entries) {
    }

    /** 初始化用上下文：依赖查找只在本次候选条目表内进行。 */
    private static final class CandidateContext implements ExtensionContext {
        private final Map<String, Object> config;
        private final Map<String, Entry> built;

        CandidateContext(Map<String, Object> config, Map<String, Entry> built) {
            this.config = config;
            this.built = built;
        }

        @Override
        public Map<String, Object> config() {
            return config;
        }

        @Override
        public Optional<Extension> dependency(String extensionId) {
            Entry entry = built.get(extensionId);
            if (entry == null || !entry.ready()) {
                return Optional.empty();
            }
            return Optional.of(entry.instance());
        }
    }

    private final Object writeLock = new Object();
    private volatile Generation current = new Generation(0, Map.of());

    /** 历史版本保留上限（可配，>=1）。 */
    private final int maxHistoryVersions;

    /** 历史版本表：编号 -> 版本；不可变 COW 发布，读路径无锁。 */
    private volatile Map<Long, HistoryVersion> history = java.util.Collections.unmodifiableMap(new LinkedHashMap<>());

    /** 测试与默认装配用：默认保留 20 个历史版本。 */
    public DefaultExtensionRegistry() {
        this(20);
    }

    /**
     * @param maxHistoryVersions 历史版本保留上限，超出后按最旧先淘汰；必须 &gt;= 1
     */
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultExtensionRegistry(
            @org.springframework.beans.factory.annotation.Value("${registry.history.max-versions:20}")
            int maxHistoryVersions) {
        if (maxHistoryVersions < 1) {
            throw new IllegalArgumentException("maxHistoryVersions must be >= 1: " + maxHistoryVersions);
        }
        this.maxHistoryVersions = maxHistoryVersions;
    }

    @Override
    public List<HistoryVersion> history() {
        // 已发布的不可变映射，按编号升序（LinkedHashMap 插入序即编号序）。
        return List.copyOf(history.values());
    }

    @Override
    public HistoryVersion historyVersion(long number) {
        HistoryVersion version = history.get(number);
        if (version == null) {
            throw new ExtensionException(ErrorCode.VERSION_NOT_FOUND,
                    "history version not found (never existed or already evicted): " + number,
                    Map.of("version", number));
        }
        return version;
    }

    @Override
    public ChangeOutcome apply(ChangeSet changeSet) {
        Objects.requireNonNull(changeSet, "changeSet");
        List<ChangeCommand> commands = changeSet.commands();
        if (commands == null || commands.isEmpty()) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST, "change set must contain at least one command",
                    Map.of());
        }

        synchronized (writeLock) {
            log.info("apply change set start: generation={}, commands={}",
                    current.number(), summarize(commands));
            return commitLocked((candidates, enabledFlags) -> {
                for (ChangeCommand command : commands) {
                    applyCommand(command, candidates, enabledFlags);
                }
            });
        }
    }

    @Override
    public ChangeOutcome rollback(long number) {
        synchronized (writeLock) {
            HistoryVersion target = history.get(number);
            if (target == null) {
                throw new ExtensionException(ErrorCode.VERSION_NOT_FOUND,
                        "cannot rollback, history version not found (never existed or already evicted): "
                                + number,
                        Map.of("version", number));
            }
            log.info("rollback start: targetVersion={}, currentGeneration={}, targetState={}",
                    number, current.number(),
                    target.extensions().stream()
                            .map(s -> s.id() + "(enabled=" + s.enabled() + ")").toList());
            try {
                return commitLocked((candidates, enabledFlags) -> {
                    // 目标版本引用的扩展必须全部仍已注册：回退不复活已移除的扩展。
                    List<String> missing = new ArrayList<>();
                    for (ExtensionStateSnapshot state : target.extensions()) {
                        if (!candidates.containsKey(state.id())) {
                            missing.add(state.id());
                        }
                    }
                    if (!missing.isEmpty()) {
                        throw new ExtensionException(ErrorCode.ROLLBACK_REJECTED,
                                "rollback rejected: target version " + number
                                        + " references extensions no longer registered: " + missing,
                                Map.of("version", number,
                                        "reason", "EXTENSIONS_NO_LONGER_REGISTERED",
                                        "missingExtensions", missing.toString()));
                    }
                    // 仅恢复运行状态（启停 + 配置）；版本/依赖声明/工厂以当前注册为准，
                    // 随后仍会走与 apply 完全相同的依赖校验与隔离初始化。
                    for (ExtensionStateSnapshot state : target.extensions()) {
                        ExtensionDefinition cur = candidates.get(state.id());
                        candidates.put(state.id(), new ExtensionDefinition(
                                cur.id(), cur.version(), cur.dependencies(), cur.enabled(),
                                Map.copyOf(state.config()), cur.factory()));
                        enabledFlags.put(state.id(), state.enabled());
                    }
                });
            } catch (ExtensionException failure) {
                if (failure.code() == ErrorCode.ROLLBACK_REJECTED) {
                    throw failure;
                }
                // 依赖校验/配置校验/初始化等失败统一归一化为可区分的回退拒绝。
                throw new ExtensionException(ErrorCode.ROLLBACK_REJECTED,
                        "rollback to version " + number + " rejected: " + failure.getMessage(),
                        Map.of("version", number, "reason", failure.code().name()),
                        failure);
            }
        }
    }

    /** 在写锁内对候选定义表做一次变更（apply 的命令序列 / rollback 的状态恢复）。 */
    private interface CandidateMutator {
        void mutate(Map<String, ExtensionDefinition> candidates, Map<String, Boolean> enabledFlags);
    }

    /**
     * 原子提交流水线（调用方必须持有写锁）：
     * 应用变更 → 依赖校验 → 隔离初始化 → 原子发布 → 留存历史 → 清理旧实例。
     * 任何一步失败，已发布世代与旧实例完全不动。
     */
    private ChangeOutcome commitLocked(CandidateMutator mutator) {
        Generation old = current;

        // 1. 在副本上应用变更，构建本次候选定义表（含 enabled 标记）。
        Map<String, ExtensionDefinition> candidates = new LinkedHashMap<>();
        Map<String, Boolean> enabledFlags = new HashMap<>();
        for (Entry entry : old.entries().values()) {
            candidates.put(entry.definition().id(), entry.definition());
            enabledFlags.put(entry.definition().id(), entry.enabled());
        }
        mutator.mutate(candidates, enabledFlags);

        // 2. 依赖校验：范围解析、缺失、版本不兼容、循环依赖（失败即整体拒绝）。
        DependencyResolver.Resolved resolved = DependencyResolver.resolve(candidates);

        // 3. 按拓扑序初始化需要初始化的扩展；可复用的实例直接进入新世代。
        Map<String, Entry> built = new LinkedHashMap<>();
        // 仅"本次新建/重建"的实例进 rollback 列表；旧实例绝不能在回滚中被关闭。
        List<Entry> createdInThisChange = new ArrayList<>();
        try {
            for (String id : resolved.topologicalOrder()) {
                buildEntry(id, candidates.get(id), enabledFlags.getOrDefault(id, false),
                        old, built, createdInThisChange);
            }
        } catch (RuntimeException failure) {
            // 整体回滚：仅丢弃本次新建实例；旧世代与旧实例完全不动，对外保持可用。
            shutdownQuietly(createdInThisChange);
            log.warn("change rejected, rolled back: generation={}, reason={}",
                    old.number(), failure.getMessage());
            throw failure;
        }

        // 4. 单一引用赋值，原子生效。
        Generation next = new Generation(old.number() + 1, Map.copyOf(built));
        current = next;

        // 5. 留存历史版本（含相对上一版本的逐项变更），超出保留上限淘汰最旧。
        recordHistory(old, next);

        // 6. 切换完成后关闭不再被新世代沿用的旧实例。
        shutdownObsolete(old, next);

        RegistrySnapshot snapshot = toSnapshot(next);
        log.info("change committed: newGeneration={}, extensionCount={}",
                next.number(), next.entries().size());
        return new ChangeOutcome(next.number(), snapshot);
    }

    @Override
    public RegistrySnapshot snapshot() {
        return toSnapshot(current);
    }

    @Override
    public Optional<ExtensionStatusView> status(String extensionId) {
        Entry entry = current.entries().get(extensionId);
        return entry == null ? Optional.empty() : Optional.of(toView(entry, current.entries()));
    }

    @Override
    public ExtensionResult invoke(ExtensionInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        String id = invocation.extensionId();
        if (id == null || id.isBlank()) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST, "extensionId must not be blank", Map.of());
        }
        // 取一次世代引用并在整个请求中沿用，保证单次执行看到一致状态。
        Generation generation = current;
        Entry entry = generation.entries().get(id);
        if (entry == null) {
            throw new ExtensionException(ErrorCode.EXTENSION_NOT_FOUND,
                    "extension not found: " + id, Map.of("extensionId", id));
        }
        if (!entry.enabled()) {
            throw new ExtensionException(ErrorCode.EXTENSION_DISABLED,
                    "extension is disabled: " + id, Map.of("extensionId", id));
        }
        if (!entry.ready()) {
            List<String> blockedBy = blockedBy(entry.definition().id(), generation.entries());
            throw new ExtensionException(ErrorCode.DEPENDENCY_NOT_READY,
                    "extension dependencies not ready: " + id,
                    Map.of("extensionId", id, "blockedBy", blockedBy.toString()));
        }
        try {
            Object data = entry.instance().execute(invocation.input() == null ? Map.of() : invocation.input());
            return ExtensionResult.ok(generation.number(), id, data);
        } catch (IllegalArgumentException e) {
            // 输入不合法视为无效请求；原始消息只进日志，不拼进对外文案与上下文。
            log.info("invalid invocation input: extensionId={}, message={}", id, e.getMessage());
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "invalid invocation input",
                    Map.of("extensionId", id), e);
        } catch (Exception e) {
            // 故障隔离：单个扩展执行失败不影响注册表与其他扩展。
            log.warn("extension execution failed: extensionId={}, generation={}, message={}",
                    id, generation.number(), e.getMessage(), e);
            throw new ExtensionException(ErrorCode.EXECUTION_FAILED,
                    "extension execution failed: " + id,
                    Map.of("extensionId", id, "generation", generation.number(),
                            "detail", String.valueOf(e.getMessage())), e);
        }
    }

    // ------------------------------------------------------------------
    // 命令处理：所有失败都是显式拒绝，绝不静默覆盖或跳过
    // ------------------------------------------------------------------

    private static void applyCommand(ChangeCommand command,
                                     Map<String, ExtensionDefinition> candidates,
                                     Map<String, Boolean> enabledFlags) {
        switch (command) {
            case ChangeCommand.RegisterDefinition register -> {
                ExtensionDefinition def = register.definition();
                if (def == null) {
                    throw new ExtensionException(ErrorCode.BAD_REQUEST,
                            "register command missing definition", Map.of());
                }
                validateDefinition(def);
                if (candidates.containsKey(def.id())) {
                    throw new ExtensionException(ErrorCode.DUPLICATE_EXTENSION_ID,
                            "extension id already exists: " + def.id(),
                            Map.of("extensionId", def.id()));
                }
                candidates.put(def.id(), def);
                enabledFlags.put(def.id(), def.enabled());
            }
            case ChangeCommand.UpdateConfig update -> {
                String id = update.extensionId();
                requireExisting(id, candidates, "update-config");
                if (update.config() == null) {
                    throw new ExtensionException(ErrorCode.BAD_REQUEST,
                            "config must not be null (use empty object to clear)",
                            Map.of("extensionId", String.valueOf(id)));
                }
                ExtensionDefinition old = candidates.get(id);
                candidates.put(id, new ExtensionDefinition(old.id(), old.version(), old.dependencies(),
                        old.enabled(), Map.copyOf(update.config()), old.factory()));
            }
            case ChangeCommand.SetEnabled toggle -> {
                String id = toggle.extensionId();
                requireExisting(id, candidates, "set-enabled");
                enabledFlags.put(id, toggle.enabled());
            }
            case ChangeCommand.RemoveExtension remove -> {
                String id = remove.extensionId();
                requireExisting(id, candidates, "remove");
                candidates.remove(id);
                enabledFlags.remove(id);
            }
        }
    }

    private static void requireExisting(String id, Map<String, ExtensionDefinition> candidates, String action) {
        if (id == null || id.isBlank() || !candidates.containsKey(id)) {
            throw new ExtensionException(ErrorCode.EXTENSION_NOT_FOUND,
                    "cannot " + action + " unknown extension: " + id,
                    Map.of("extensionId", String.valueOf(id)));
        }
    }

    private static void validateDefinition(ExtensionDefinition def) {
        if (def.id() == null || def.id().isBlank()) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST, "extension id must not be blank", Map.of());
        }
        if (def.version() == null) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "extension version must not be null", Map.of("extensionId", def.id()));
        }
        if (def.factory() == null) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "extension factory must not be null", Map.of("extensionId", def.id()));
        }
        if (def.dependencies() == null || def.config() == null) {
            throw new ExtensionException(ErrorCode.BAD_REQUEST,
                    "extension dependencies/config must not be null", Map.of("extensionId", def.id()));
        }
    }

    // ------------------------------------------------------------------
    // 隔离初始化
    // ------------------------------------------------------------------

    private void buildEntry(String id,
                            ExtensionDefinition def,
                            boolean enabled,
                            Generation old,
                            Map<String, Entry> built,
                            List<Entry> createdInThisChange) {
        Entry oldEntry = old.entries().get(id);

        // 判断就绪：enabled 且所有依赖条目都 ready（拓扑序保证依赖已构建）。
        boolean ready = enabled;
        List<String> blockedBy = new ArrayList<>();
        if (enabled) {
            for (var req : def.dependencies()) {
                Entry depEntry = built.get(req.extensionId());
                if (depEntry == null || !depEntry.ready()) {
                    ready = false;
                    blockedBy.add(req.extensionId());
                }
            }
        }

        if (!ready) {
            // 用户意图仍是启用（enabled=true），但依赖链未就绪（ready=false）：
            // 不创建/初始化实例，执行时归一化为 DEPENDENCY_NOT_READY；
            // 旧实例的关闭在成功提交后由 shutdownObsolete 处理，回滚路径不触碰旧实例。
            built.put(id, new Entry(def, null, enabled, false));
            return;
        }

        // 自身与依赖均无变化时复用旧实例，避免重复初始化。
        boolean dependencyReusedUnchanged = true;
        for (var req : def.dependencies()) {
            Entry oldDep = old.entries().get(req.extensionId());
            Entry newDep = built.get(req.extensionId());
            if (oldDep == null || oldDep.instance() != newDep.instance()) {
                dependencyReusedUnchanged = false;
                break;
            }
        }
        boolean selfUnchanged = oldEntry != null
                && oldEntry.enabled()
                && oldEntry.ready()
                && sameDefinition(oldEntry.definition(), def);

        if (selfUnchanged && dependencyReusedUnchanged) {
            built.put(id, new Entry(def, oldEntry.instance(), true, true));
            return;
        }

        // 需要（重新）初始化：创建新实例并在隔离上下文中初始化。
        Extension instance = createInstance(def);
        Entry newEntry = new Entry(def, instance, true, true);
        built.put(id, newEntry);
        CandidateContext context = new CandidateContext(Map.copyOf(def.config()), built);
        try {
            instance.initialize(context);
        } catch (IllegalArgumentException e) {
            throw new ExtensionException(ErrorCode.INVALID_CONFIG,
                    "invalid config for extension '" + id + "': " + e.getMessage(),
                    Map.of("extensionId", id, "config", safeConfig(def.config()),
                            "detail", String.valueOf(e.getMessage())), e);
        } catch (Exception e) {
            log.warn("extension initialization failed: extensionId={}, message={}", id, e.getMessage(), e);
            throw new ExtensionException(ErrorCode.INITIALIZATION_FAILED,
                    "extension initialization failed: " + id,
                    Map.of("extensionId", id, "config", safeConfig(def.config()),
                            "detail", String.valueOf(e.getMessage())), e);
        }
        createdInThisChange.add(newEntry);
    }

    private static Extension createInstance(ExtensionDefinition def) {
        try {
            Extension instance = def.factory().create();
            if (instance == null) {
                throw new ExtensionException(ErrorCode.INITIALIZATION_FAILED,
                        "factory returned null: " + def.id(),
                        Map.of("extensionId", def.id()));
            }
            return instance;
        } catch (ExtensionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ExtensionException(ErrorCode.INITIALIZATION_FAILED,
                    "factory threw while creating extension: " + def.id(),
                    Map.of("extensionId", def.id(), "detail", String.valueOf(e.getMessage())), e);
        }
    }

    private static boolean sameDefinition(ExtensionDefinition a, ExtensionDefinition b) {
        return Objects.equals(a.version(), b.version())
                && Objects.equals(a.dependencies(), b.dependencies())
                && Objects.equals(a.config(), b.config());
    }

    // ------------------------------------------------------------------
    // 历史版本：留存、diff 与淘汰（仅在写锁内、成功提交后调用）
    // ------------------------------------------------------------------

    private void recordHistory(Generation old, Generation next) {
        HistoryVersion version = new HistoryVersion(
                next.number(),
                List.copyOf(diff(old, next)),
                List.copyOf(stateSnapshots(next)));

        Map<Long, HistoryVersion> updated = new LinkedHashMap<>(history);
        updated.put(version.number(), version);
        // 超出保留上限：按最旧的先淘汰（插入序即编号序）。
        while (updated.size() > maxHistoryVersions) {
            Long oldest = updated.keySet().iterator().next();
            updated.remove(oldest);
            log.info("history version evicted (retention={}): number={}", maxHistoryVersions, oldest);
        }
        history = java.util.Collections.unmodifiableMap(updated);
        log.info("history version recorded: number={}, changes={}, retained={}",
                version.number(), summarizeChanges(version.changes()), updated.size());
    }

    /** 逐项对比两个世代，产出可读的变更清单（启停、配置、注册/移除/重定义）。 */
    private static List<ExtensionChange> diff(Generation old, Generation next) {
        List<ExtensionChange> changes = new ArrayList<>();
        for (Entry newEntry : next.entries().values()) {
            String id = newEntry.definition().id();
            Entry oldEntry = old.entries().get(id);
            if (oldEntry == null) {
                changes.add(new ExtensionChange(id, ExtensionChange.ChangeKind.REGISTERED,
                        null, Map.copyOf(newEntry.definition().config())));
                continue;
            }
            ExtensionDefinition oldDef = oldEntry.definition();
            ExtensionDefinition newDef = newEntry.definition();
            if (!Objects.equals(oldDef.version(), newDef.version())
                    || !Objects.equals(oldDef.dependencies(), newDef.dependencies())) {
                changes.add(new ExtensionChange(id, ExtensionChange.ChangeKind.REDEFINED, null, null));
            }
            if (oldEntry.enabled() != newEntry.enabled()) {
                changes.add(new ExtensionChange(id,
                        newEntry.enabled() ? ExtensionChange.ChangeKind.ENABLED
                                : ExtensionChange.ChangeKind.DISABLED,
                        null, null));
            }
            if (!Objects.equals(oldDef.config(), newDef.config())) {
                changes.add(new ExtensionChange(id, ExtensionChange.ChangeKind.CONFIG_CHANGED,
                        Map.copyOf(oldDef.config()), Map.copyOf(newDef.config())));
            }
        }
        for (Entry oldEntry : old.entries().values()) {
            String id = oldEntry.definition().id();
            if (!next.entries().containsKey(id)) {
                changes.add(new ExtensionChange(id, ExtensionChange.ChangeKind.REMOVED,
                        Map.copyOf(oldEntry.definition().config()), null));
            }
        }
        return changes;
    }

    /** 把一个世代的全部条目转成可留存的只读状态（不含实例与工厂）。 */
    private static List<ExtensionStateSnapshot> stateSnapshots(Generation generation) {
        List<ExtensionStateSnapshot> states = new ArrayList<>();
        for (Entry entry : generation.entries().values()) {
            Map<String, String> deps = new LinkedHashMap<>();
            entry.definition().dependencies()
                    .forEach(req -> deps.put(req.extensionId(), req.versionRangeRaw()));
            states.add(new ExtensionStateSnapshot(
                    entry.definition().id(),
                    entry.definition().version().toString(),
                    entry.enabled(),
                    Map.copyOf(deps),
                    Map.copyOf(entry.definition().config())));
        }
        return states;
    }

    private static List<String> summarizeChanges(List<ExtensionChange> changes) {
        return changes.stream()
                .map(change -> change.kind() + "(" + change.extensionId() + ")")
                .toList();
    }

    // ------------------------------------------------------------------
    // 生命周期清理
    // ------------------------------------------------------------------

    private void shutdownObsolete(Generation old, Generation next) {
        List<Entry> obsolete = new ArrayList<>();
        for (Entry oldEntry : old.entries().values()) {
            if (oldEntry.instance() == null) {
                continue;
            }
            Entry newEntry = next.entries().get(oldEntry.definition().id());
            if (newEntry == null || newEntry.instance() != oldEntry.instance()) {
                obsolete.add(oldEntry);
            }
        }
        shutdownQuietly(obsolete);
    }

    private void shutdownQuietly(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.instance() == null) {
                continue;
            }
            try {
                entry.instance().shutdown();
            } catch (Exception e) {
                // 关闭失败只记录，不影响已生效结论。
                log.warn("extension shutdown failed: extensionId={}, message={}",
                        entry.definition().id(), e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // 快照视图
    // ------------------------------------------------------------------

    private static RegistrySnapshot toSnapshot(Generation generation) {
        List<ExtensionStatusView> views = new ArrayList<>();
        for (Entry entry : generation.entries().values()) {
            views.add(toView(entry, generation.entries()));
        }
        return new RegistrySnapshot(generation.number(), List.copyOf(views));
    }

    private static ExtensionStatusView toView(Entry entry, Map<String, Entry> all) {
        Map<String, String> deps = new LinkedHashMap<>();
        entry.definition().dependencies()
                .forEach(req -> deps.put(req.extensionId(), req.versionRangeRaw()));
        return new ExtensionStatusView(
                entry.definition().id(),
                entry.definition().version().toString(),
                entry.enabled(),
                entry.ready(),
                Map.copyOf(deps),
                entry.ready() ? List.of() : blockedBy(entry.definition().id(), all),
                Map.copyOf(entry.definition().config()));
    }

    private static List<String> blockedBy(String id, Map<String, Entry> all) {
        Entry entry = all.get(id);
        if (entry == null) {
            return List.of();
        }
        List<String> blocked = new ArrayList<>();
        for (var req : entry.definition().dependencies()) {
            Entry dep = all.get(req.extensionId());
            if (dep == null || !dep.ready()) {
                blocked.add(req.extensionId());
            }
        }
        return List.copyOf(blocked);
    }

    /** 配置可能含敏感值，日志中做键名级输出，值仅以类型占位。 */
    private static Map<String, Object> safeConfig(Map<String, Object> config) {
        Map<String, Object> safe = new TreeMap<>();
        config.forEach((key, value) -> safe.put(key,
                value == null ? "null" : "<" + value.getClass().getSimpleName() + ">"));
        return Map.copyOf(safe);
    }

    private static List<String> summarize(List<ChangeCommand> commands) {
        return commands.stream().map(command -> switch (command) {
            case ChangeCommand.RegisterDefinition r -> "register(" + r.definition().id() + ")";
            case ChangeCommand.UpdateConfig u -> "update-config(" + u.extensionId() + ")";
            case ChangeCommand.SetEnabled s -> "set-enabled(" + s.extensionId() + "=" + s.enabled() + ")";
            case ChangeCommand.RemoveExtension r -> "remove(" + r.extensionId() + ")";
        }).toList();
    }
}
