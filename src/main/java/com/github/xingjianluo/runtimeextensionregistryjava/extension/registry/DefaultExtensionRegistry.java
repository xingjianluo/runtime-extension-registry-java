package com.github.xingjianluo.runtimeextensionregistryjava.extension.registry;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeOutcome;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.EntryChange;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryPage;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistorySummary;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.RegistrySnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.VersionDiff;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.VersionKind;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionFactory;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
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
 *   <li>每次成功提交（含回退）追加一个带递增编号的历史版本，记录相对上一版本的启停/配置差异；
 *       历史条数有上限，超出后最旧版本先淘汰，查询已淘汰/不存在的编号明确报"查不到"。</li>
 * </ul>
 */
@Component
public class DefaultExtensionRegistry implements ExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(DefaultExtensionRegistry.class);

    /** 历史版本默认保留条数上限。 */
    public static final int DEFAULT_RETENTION_LIMIT = 20;

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

    /** 历史保留条数上限（保留最近 N 个成功提交版本）。 */
    private final int retentionLimit;
    /** 已留存历史版本：队首最旧、队尾最新；仅在 writeLock 内增删。 */
    private final Deque<HistoryVersion> history = new ArrayDeque<>();

    /** 供 Spring 注入使用：保留上限可通过 {@code extension.history.retention-limit} 配置。 */
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultExtensionRegistry(
            @org.springframework.beans.factory.annotation.Value("${extension.history.retention-limit:20}")
            int retentionLimit) {
        if (retentionLimit < 1) {
            throw new IllegalArgumentException("extension.history.retention-limit must be >= 1");
        }
        this.retentionLimit = retentionLimit;
    }

    /** 供单元测试使用：采用默认保留上限 {@value #DEFAULT_RETENTION_LIMIT}。 */
    public DefaultExtensionRegistry() {
        this(DEFAULT_RETENTION_LIMIT);
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
            // 1. 在副本上按序应用命令，构建本次候选定义表（含 enabled 标记）。
            Map<String, ExtensionDefinition> candidates = new LinkedHashMap<>();
            Map<String, Boolean> enabledFlags = new HashMap<>();
            for (Entry entry : current.entries().values()) {
                candidates.put(entry.definition().id(), entry.definition());
                enabledFlags.put(entry.definition().id(), entry.enabled());
            }
            for (ChangeCommand command : commands) {
                applyCommand(command, candidates, enabledFlags);
            }
            return commitLocked(candidates, enabledFlags, VersionKind.CHANGE, null, summarize(commands));
        }
    }

    @Override
    public HistoryPage history() {
        synchronized (writeLock) {
            List<HistorySummary> summaries = new ArrayList<>(history.size());
            // 从新到旧排列。
            for (var it = history.descendingIterator(); it.hasNext(); ) {
                summaries.add(toSummary(it.next()));
            }
            return new HistoryPage(retentionLimit, history.size(), List.copyOf(summaries));
        }
    }

    @Override
    public Optional<HistoryVersion> historyVersion(long version) {
        synchronized (writeLock) {
            for (HistoryVersion hv : history) {
                if (hv.version() == version) {
                    return Optional.of(hv);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public ChangeOutcome rollback(long version) {
        if (version < 1) {
            throw new ExtensionException(ErrorCode.HISTORY_VERSION_NOT_FOUND,
                    "history version not found: " + version, Map.of("version", version));
        }
        synchronized (writeLock) {
            HistoryVersion target = null;
            for (HistoryVersion hv : history) {
                if (hv.version() == version) {
                    target = hv;
                    break;
                }
            }
            if (target == null) {
                // 明确区分"查不到"：可能压根不存在，也可能已被保留上限淘汰。
                throw new ExtensionException(ErrorCode.HISTORY_VERSION_NOT_FOUND,
                        "history version not found or already evicted: " + version,
                        new LinkedHashMap<>(Map.of(
                                "version", version,
                                "retentionLimit", retentionLimit,
                                "oldestRetained", history.isEmpty() ? 0L : history.peekFirst().version(),
                                "latestRetained", history.isEmpty() ? 0L : history.peekLast().version())));
            }

            Generation old = current;
            Map<String, ExtensionStatusView> targetViews = new LinkedHashMap<>();
            for (ExtensionStatusView view : target.snapshot().extensions()) {
                targetViews.put(view.id(), view);
            }

            // 结构一致性闸门：目标版本与当前已注册扩展集合必须完全一致。
            // 目标里含现已不存在的扩展（被移除），或当前多出目标之后才注册的扩展，一律整体拒绝。
            List<String> missingNow = new ArrayList<>();
            for (String id : targetViews.keySet()) {
                if (!old.entries().containsKey(id)) {
                    missingNow.add(id);
                }
            }
            List<String> addedAfter = new ArrayList<>();
            for (String id : old.entries().keySet()) {
                if (!targetViews.containsKey(id)) {
                    addedAfter.add(id);
                }
            }
            if (!missingNow.isEmpty() || !addedAfter.isEmpty()) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("targetVersion", version);
                ctx.put("currentVersion", old.number());
                ctx.put("missingInCurrent", List.copyOf(missingNow));
                ctx.put("addedAfterTarget", List.copyOf(addedAfter));
                log.warn("rollback rejected on extension-set mismatch: targetVersion={}, missingNow={}, addedAfter={}",
                        version, missingNow, addedAfter);
                throw new ExtensionException(ErrorCode.ROLLBACK_NOT_POSSIBLE,
                        "rollback target extension set does not match current registry: " + version, ctx);
            }

            // 以"当前定义（版本/依赖/工厂）"为底，恢复目标版本的配置与启用状态。
            Map<String, ExtensionDefinition> candidates = new LinkedHashMap<>();
            Map<String, Boolean> enabledFlags = new HashMap<>();
            for (Entry entry : old.entries().values()) {
                ExtensionDefinition def = entry.definition();
                ExtensionStatusView view = targetViews.get(def.id());
                Map<String, Object> targetConfig = new LinkedHashMap<>(view.config());
                candidates.put(def.id(), new ExtensionDefinition(def.id(), def.version(), def.dependencies(),
                        def.enabled(), targetConfig, def.factory()));
                enabledFlags.put(def.id(), view.enabled());
            }

            log.info("rollback start: fromGeneration={} toTargetVersion={}", old.number(), version);
            return commitLocked(candidates, enabledFlags, VersionKind.ROLLBACK, version,
                    List.of("rollback->" + version));
        }
    }

    /**
     * 在已持有 {@link #writeLock} 的前提下完成一次原子提交，常规变更与回退共用同一流程：
     * 依赖校验 → 拓扑序隔离初始化 → 单一引用发布 → 关闭旧实例 → 留存历史（含差异与淘汰）。
     */
    private ChangeOutcome commitLocked(Map<String, ExtensionDefinition> candidates,
                                       Map<String, Boolean> enabledFlags,
                                       VersionKind kind,
                                       Long revertedFrom,
                                       List<String> summary) {
        Generation old = current;

        log.info("commit start: kind={}, generation={}, operations={}", kind, old.number(), summary);

        Map<String, Entry> built = new LinkedHashMap<>();
        // 仅"本次新建/重建"的实例进 rollback 列表；旧实例绝不能在回滚中被关闭。
        List<Entry> createdInThisChange = new ArrayList<>();
        try {
            // 2. 依赖校验：范围解析、缺失、版本不兼容、循环依赖（失败即整体拒绝）。
            DependencyResolver.Resolved resolved = DependencyResolver.resolve(candidates);
            // 3. 按拓扑序初始化需要初始化的扩展；可复用的实例直接进入新世代。
            for (String id : resolved.topologicalOrder()) {
                buildEntry(id, candidates.get(id), enabledFlags.getOrDefault(id, false),
                        old, built, createdInThisChange);
            }
        } catch (RuntimeException failure) {
            // 整体回滚：仅丢弃本次新建实例；旧世代与旧实例完全不动，对外保持可用。
            shutdownQuietly(createdInThisChange);
            if (kind == VersionKind.ROLLBACK) {
                log.warn("rollback rejected during validation/initialization: targetVersion={}, reason={}",
                        revertedFrom, failure.getMessage());
                throw wrapRollbackFailure(revertedFrom, old.number(), failure);
            }
            log.warn("apply change set rejected, rolled back: generation={}, reason={}",
                    old.number(), failure.getMessage());
            throw failure;
        }

        // 4. 单一引用赋值，原子生效。
        Generation next = new Generation(old.number() + 1, Map.copyOf(built));
        VersionDiff diff = computeDiff(old, next, kind);
        current = next;

        // 5. 切换完成后关闭不再被新世代沿用的旧实例。
        shutdownObsolete(old, next);

        RegistrySnapshot snapshot = toSnapshot(next);
        appendHistory(new HistoryVersion(next.number(), kind, revertedFrom, diff, snapshot));

        log.info("commit committed: kind={}, newVersion={}, extensionCount={}, changedEntries={}",
                kind, next.number(), next.entries().size(), diff.changes().size());
        return new ChangeOutcome(next.number(), snapshot);
    }

    private static ExtensionException wrapRollbackFailure(Long targetVersion, long currentVersion,
                                                          RuntimeException failure) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("targetVersion", targetVersion);
        ctx.put("currentVersion", currentVersion);
        if (failure instanceof ExtensionException ee) {
            ctx.put("reason", ee.code().name());
            ctx.put("reasonContext", ee.context().toString());
        } else {
            ctx.put("reason", failure.getClass().getSimpleName());
        }
        return new ExtensionException(ErrorCode.ROLLBACK_NOT_POSSIBLE,
                "rollback target cannot be made ready under current dependencies: " + targetVersion,
                Map.copyOf(ctx), failure);
    }

    private void appendHistory(HistoryVersion version) {
        history.addLast(version);
        while (history.size() > retentionLimit) {
            HistoryVersion evicted = history.removeFirst();
            log.info("history version evicted (oldest-first): version={}, retained={}, limit={}",
                    evicted.version(), history.size(), retentionLimit);
        }
    }

    private static HistorySummary toSummary(HistoryVersion hv) {
        List<String> changedIds = hv.diff().changes().stream()
                .map(EntryChange::extensionId)
                .toList();
        return new HistorySummary(hv.version(), hv.kind(), hv.revertedFrom(),
                hv.diff().baseVersion(), changedIds);
    }

    /** 计算新世代相对上一已提交世代，每个扩展的启停与配置差异。 */
    private static VersionDiff computeDiff(Generation old, Generation next, VersionKind kind) {
        List<EntryChange> changes = new ArrayList<>();
        // 并集按标识排序，保证差异输出稳定、可读。
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        ids.addAll(old.entries().keySet());
        ids.addAll(next.entries().keySet());
        for (String id : ids) {
            Entry before = old.entries().get(id);
            Entry after = next.entries().get(id);
            if (before == null) {
                changes.add(new EntryChange(EntryChange.Type.ADDED, id,
                        null, after.enabled(), null, Map.copyOf(after.definition().config())));
                continue;
            }
            if (after == null) {
                changes.add(new EntryChange(EntryChange.Type.REMOVED, id,
                        before.enabled(), null, Map.copyOf(before.definition().config()), null));
                continue;
            }
            boolean toggled = before.enabled() != after.enabled();
            boolean configChanged = !Objects.equals(before.definition().config(), after.definition().config());
            if (!toggled && !configChanged) {
                continue;
            }
            EntryChange.Type type = toggled && configChanged
                    ? EntryChange.Type.TOGGLED_AND_CONFIG_CHANGED
                    : toggled ? EntryChange.Type.ENABLED_TOGGLED : EntryChange.Type.CONFIG_CHANGED;
            changes.add(new EntryChange(type, id,
                    before.enabled(), after.enabled(),
                    Map.copyOf(before.definition().config()), Map.copyOf(after.definition().config())));
        }
        return new VersionDiff(next.number(), old.number(), kind, List.copyOf(changes));
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
