package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.EntryChange;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStatusView;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryPage;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.VersionKind;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史版本留存、差异查询与保留淘汰边界验证：
 * <ol>
 *   <li>每次成功提交产生严格递增编号，可按编号查到完整状态；</li>
 *   <li>差异视图准确刻画相对上一版本"动了哪些扩展的启停与配置"；</li>
 *   <li>保留条数可配，超出上限最旧先淘汰；已淘汰/不存在的编号明确报"查不到"；</li>
 *   <li>被拒绝的变更不产生历史、不推进编号。</li>
 * </ol>
 */
class HistoryAndEvictionTest {

    private static final Logger log = LoggerFactory.getLogger(HistoryAndEvictionTest.class);

    /** 任何非空字符串配置都合法，仅用于计数与持有配置的稳定扩展。 */
    static class Probe implements Extension {
        final AtomicInteger inits;
        volatile Map<String, Object> activeConfig = Map.of();

        Probe(AtomicInteger inits) {
            this.inits = inits;
        }

        @Override
        public void initialize(ExtensionContext context) {
            Object v = context.config().get("v");
            if (v != null && !(v instanceof String)) {
                throw new IllegalArgumentException("v must be a string");
            }
            if ("bad".equals(v)) {
                throw new IllegalArgumentException("v must not be bad");
            }
            activeConfig = Map.copyOf(context.config());
            inits.incrementAndGet();
        }

        @Override
        public Object execute(Map<String, Object> input) {
            return activeConfig;
        }
    }

    private final AtomicInteger inits = new AtomicInteger();

    private ExtensionDefinition def(String id, Map<String, Object> config, boolean enabled) {
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled, config,
                () -> new Probe(inits));
    }

    private ChangeSet update(String id, String value) {
        return new ChangeSet(List.of(new ChangeCommand.UpdateConfig(id, Map.of("v", value))));
    }

    @Test
    void successfulCommitsProduceIncrementingVersionsWithAccurateDiffs() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();

        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(def("a", Map.of("v", "a0"), true)),
                new ChangeCommand.RegisterDefinition(def("b", Map.of("v", "b0"), false)))))
                .generation();
        log.info("scenario=versions input=register(a=enabled,b=disabled) => v{}", v1);
        long v2 = registry.apply(update("a", "a1")).generation();
        log.info("scenario=versions input=update-config(a,a1) => v{}", v2);
        long v3 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.SetEnabled("b", true),
                new ChangeCommand.UpdateConfig("b", Map.of("v", "b1")))))
                .generation();
        log.info("scenario=versions input=enable(b)+update-config(b,b1) => v{}", v3);

        assertThat(v2).isEqualTo(v1 + 1);
        assertThat(v3).isEqualTo(v2 + 1);

        // 首个版本：两条 ADDED，baseVersion=0
        HistoryVersion h1 = registry.historyVersion(v1).orElseThrow();
        assertThat(h1.kind()).isEqualTo(VersionKind.CHANGE);
        assertThat(h1.diff().baseVersion()).isZero();
        assertThat(h1.diff().changes()).extracting(EntryChange::extensionId)
                .containsExactly("a", "b");
        assertThat(h1.diff().changes()).allMatch(c -> c.type() == EntryChange.Type.ADDED);

        // v2：仅 a 的 CONFIG_CHANGED，前后配置都可追溯
        EntryChange cA = registry.historyVersion(v2).orElseThrow().diff().changes().get(0);
        assertThat(cA.extensionId()).isEqualTo("a");
        assertThat(cA.type()).isEqualTo(EntryChange.Type.CONFIG_CHANGED);
        assertThat(cA.configBefore()).isEqualTo(Map.of("v", "a0"));
        assertThat(cA.configAfter()).isEqualTo(Map.of("v", "a1"));
        log.info("scenario=diff v{} changeOn=a type={} before={} after={}", v2, cA.type(),
                cA.configBefore(), cA.configAfter());

        // v3：b 同时启停+配置变化
        EntryChange cB = registry.historyVersion(v3).orElseThrow().diff().changes().get(0);
        assertThat(cB.extensionId()).isEqualTo("b");
        assertThat(cB.type()).isEqualTo(EntryChange.Type.TOGGLED_AND_CONFIG_CHANGED);
        assertThat(cB.enabledBefore()).isFalse();
        assertThat(cB.enabledAfter()).isTrue();

        // 按编号能查到提交时刻的完整状态
        ExtensionStatusView aAtV2 = registry.historyVersion(v2).orElseThrow().snapshot().extensions()
                .stream().filter(e -> e.id().equals("a")).findFirst().orElseThrow();
        assertThat(aAtV2.config()).isEqualTo(Map.of("v", "a1"));
        ExtensionStatusView aAtV1 = registry.historyVersion(v1).orElseThrow().snapshot().extensions()
                .stream().filter(e -> e.id().equals("a")).findFirst().orElseThrow();
        assertThat(aAtV1.config()).isEqualTo(Map.of("v", "a0"));
    }

    @Test
    void historyListIsNewestFirstAndRejectedCommitsCreateNoVersion() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        long v1 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(def("a", Map.of("v", "ok"), true)))))
                .generation();
        long v2 = registry.apply(update("a", "ok2")).generation();

        // 一次非法变更：必须被拒绝，不产生历史、不推进编号
        assertThatThrownBy(() -> registry.apply(update("a", "bad")))
                .isInstanceOf(ExtensionException.class)
                .extracting(e -> ((ExtensionException) e).code())
                .isEqualTo(ErrorCode.INVALID_CONFIG);
        log.info("scenario=rejected-no-history input=update-config(a,bad) expected=INVALID_CONFIG currentVersion={}",
                registry.snapshot().generation());

        HistoryPage page = registry.history();
        assertThat(page.retentionLimit()).isEqualTo(DefaultExtensionRegistry.DEFAULT_RETENTION_LIMIT);
        assertThat(page.versions()).extracting(h -> h.version()).containsExactly(v2, v1);
        assertThat(page.versions().get(0).changedIds()).containsExactly("a");
        // 当前世代仍是 v2，非法变更未推进
        assertThat(registry.snapshot().generation()).isEqualTo(v2);
    }

    @Test
    void evictionDropsOldestFirstAndEvictedOrUnknownVersionsReportNotFound() {
        int limit = 3;
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(limit);
        long first = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(def("a", Map.of("v", "0"), true)))))
                .generation();

        // 再提交 limit 次（总成功版本数 = limit+1），最早的一个应被淘汰
        long last = first;
        for (int i = 1; i <= limit; i++) {
            last = registry.apply(update("a", "v" + i)).generation();
            log.info("scenario=eviction limit={} afterCommit#{} version={} retainedCount={}",
                    limit, i, last, registry.history().retainedCount());
        }
        final long evictedVersion = first;
        final long finalLast = last;

        HistoryPage page = registry.history();
        assertThat(page.retainedCount()).isEqualTo(limit);
        assertThat(page.retentionLimit()).isEqualTo(limit);
        // 保留的是最近 limit 个，最旧的 first 已淘汰
        assertThat(registry.historyVersion(evictedVersion)).isEmpty();
        assertThat(registry.historyVersion(finalLast)).isPresent();
        assertThat(page.versions().get(0).version()).isEqualTo(finalLast);
        assertThat(page.versions().get(page.versions().size() - 1).version())
                .isEqualTo(evictedVersion + 1);

        // 已淘汰版本：明确"查不到"，错误码与普通不存在区分于其它失败，但二者同为 not-found
        assertThatThrownBy(() -> registry.rollback(evictedVersion))
                .isInstanceOf(ExtensionException.class)
                .extracting(e -> ((ExtensionException) e).code())
                .isEqualTo(ErrorCode.HISTORY_VERSION_NOT_FOUND);
        // 压根不存在的编号
        assertThatThrownBy(() -> historyVersionOrThrow(registry, finalLast + 100))
                .isInstanceOf(ExtensionException.class)
                .extracting(e -> ((ExtensionException) e).code())
                .isEqualTo(ErrorCode.HISTORY_VERSION_NOT_FOUND);
        // 非法编号
        assertThatThrownBy(() -> registry.rollback(0))
                .isInstanceOf(ExtensionException.class)
                .extracting(e -> ((ExtensionException) e).code())
                .isEqualTo(ErrorCode.HISTORY_VERSION_NOT_FOUND);
    }

    /** 测试辅助：把 Optional 空结果转成异常，模拟 Web 层的归一化。 */
    private static HistoryVersion historyVersionOrThrow(DefaultExtensionRegistry registry, long version) {
        return registry.historyVersion(version).orElseThrow(() ->
                new ExtensionException(ErrorCode.HISTORY_VERSION_NOT_FOUND,
                        "history version not found: " + version, Map.of("version", version)));
    }
}
