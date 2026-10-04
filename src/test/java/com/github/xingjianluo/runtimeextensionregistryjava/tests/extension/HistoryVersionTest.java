package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionChange;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionStateSnapshot;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.HistoryVersion;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史版本留存：递增编号、逐项变更（diff）、按号查询、保留上限与淘汰边界、
 * 查不到（已淘汰/从未存在）时的可区分拒绝。
 */
class HistoryVersionTest {

    private static final Logger log = LoggerFactory.getLogger(HistoryVersionTest.class);

    /** 接受任意配置的探针扩展。 */
    static class AnyConfigExtension implements Extension {
        @Override
        public void initialize(ExtensionContext context) {
        }
    }

    /** 校验 k 必须为非空字符串的探针扩展（用于制造可被拒绝的变更）。 */
    static class StrictExtension implements Extension {
        @Override
        public void initialize(ExtensionContext context) {
            Object v = context.config().get("k");
            if (!(v instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("k must be a non-blank string");
            }
        }
    }

    private static ExtensionDefinition anyConfig(String id, Map<String, Object> config, boolean enabled) {
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), enabled, config,
                AnyConfigExtension::new);
    }

    private static ExtensionDefinition strict(String id, Map<String, Object> config) {
        return new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), true, config,
                StrictExtension::new);
    }

    @Test
    void everySuccessfulCommitRecordsVersionWithIncreasingNumbersAndDiff() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        assertThat(registry.history()).isEmpty();

        // v1: 注册 a（启用）与 b（停用）
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(anyConfig("a", Map.of("k", "a1"), true)),
                new ChangeCommand.RegisterDefinition(anyConfig("b", Map.of("k", "b1"), false)))));
        // v2: 改 a 的配置 + 启用 b
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("a", Map.of("k", "a2")),
                new ChangeCommand.SetEnabled("b", true))));
        // v3: 停用 a
        registry.apply(new ChangeSet(List.of(new ChangeCommand.SetEnabled("a", false))));

        List<HistoryVersion> history = registry.history();
        log.info("history numbers={} (expect strictly increasing)", 
                history.stream().map(HistoryVersion::number).toList());
        assertThat(history.stream().map(HistoryVersion::number).toList())
                .containsExactly(1L, 2L, 3L);

        // v1 的 diff：两个 REGISTERED
        HistoryVersion v1 = registry.historyVersion(1);
        assertThat(v1.changes()).extracting(ExtensionChange::kind)
                .containsExactlyInAnyOrder(ExtensionChange.ChangeKind.REGISTERED,
                        ExtensionChange.ChangeKind.REGISTERED);
        assertThat(v1.extensions()).extracting(ExtensionStateSnapshot::id)
                .containsExactlyInAnyOrder("a", "b");

        // v2 的 diff：a 配置变更（含前后值）、b 启用
        HistoryVersion v2 = registry.historyVersion(2);
        log.info("v2 changes={}", v2.changes());
        ExtensionChange aConfig = v2.changes().stream()
                .filter(c -> c.extensionId().equals("a")).findFirst().orElseThrow();
        assertThat(aConfig.kind()).isEqualTo(ExtensionChange.ChangeKind.CONFIG_CHANGED);
        assertThat(aConfig.oldConfig()).isEqualTo(Map.of("k", "a1"));
        assertThat(aConfig.newConfig()).isEqualTo(Map.of("k", "a2"));
        ExtensionChange bEnabled = v2.changes().stream()
                .filter(c -> c.extensionId().equals("b")).findFirst().orElseThrow();
        assertThat(bEnabled.kind()).isEqualTo(ExtensionChange.ChangeKind.ENABLED);

        // v3 的 diff：a 停用；v3 留存的状态与当前对外状态一致
        HistoryVersion v3 = registry.historyVersion(3);
        assertThat(v3.changes()).singleElement().satisfies(c -> {
            assertThat(c.extensionId()).isEqualTo("a");
            assertThat(c.kind()).isEqualTo(ExtensionChange.ChangeKind.DISABLED);
        });
        ExtensionStateSnapshot aState = v3.extensions().stream()
                .filter(s -> s.id().equals("a")).findFirst().orElseThrow();
        assertThat(aState.enabled()).isFalse();
        assertThat(aState.config()).isEqualTo(Map.of("k", "a2"));
        assertThat(registry.status("a").orElseThrow().enabled()).isFalse();
    }

    @Test
    void rejectedCommitRecordsNoHistoryAndDoesNotAdvanceNumbers() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(strict("s", Map.of("k", "ok"))))));
        assertThat(registry.history()).hasSize(1);

        log.info("rejected-change input: config={{k:''}} expect=INVALID_CONFIG, no new history version");
        assertThatThrownBy(() -> registry.apply(new ChangeSet(List.of(
                new ChangeCommand.UpdateConfig("s", Map.of("k", ""))))))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.INVALID_CONFIG);

        assertThat(registry.history()).hasSize(1);
        assertThat(registry.history().get(0).number()).isEqualTo(1);
    }

    @Test
    void retentionEvictsOldestAndMissingVersionsAreDistinguishable() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry(3);
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(anyConfig("a", Map.of("k", "v0"), true)))));
        for (int i = 1; i <= 4; i++) {
            registry.apply(new ChangeSet(List.of(
                    new ChangeCommand.UpdateConfig("a", Map.of("k", "v" + i)))));
        }
        // 共 5 次成功提交，上限 3 -> 仅保留 3、4、5
        List<Long> retained = registry.history().stream().map(HistoryVersion::number).toList();
        log.info("retention=3, commits=5, retained={} (expect [3, 4, 5])", retained);
        assertThat(retained).containsExactly(3L, 4L, 5L);

        // 边界：最新一条与最旧一条留存版本都可查
        assertThat(registry.historyVersion(3).number()).isEqualTo(3);
        assertThat(registry.historyVersion(5).number()).isEqualTo(5);

        // 已被淘汰的版本：明确 VERSION_NOT_FOUND，而不是空结果或内部错误
        log.info("query evicted version=1 expect=VERSION_NOT_FOUND");
        assertThatThrownBy(() -> registry.historyVersion(1))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.VERSION_NOT_FOUND);
                    assertThat(ee.context()).containsEntry("version", 1L);
                });

        // 从未产生过的版本（远超当前编号 / 非正编号）：同样 VERSION_NOT_FOUND
        for (long missing : new long[]{2L, 999L, 0L, -1L}) {
            log.info("query never-existed version={} expect=VERSION_NOT_FOUND", missing);
            assertThatThrownBy(() -> registry.historyVersion(missing))
                    .isInstanceOf(ExtensionException.class)
                    .extracting(ex -> ((ExtensionException) ex).code())
                    .isEqualTo(ErrorCode.VERSION_NOT_FOUND);
        }
    }

    @Test
    void removeAndReregisterAreVisibleInDiff() {
        DefaultExtensionRegistry registry = new DefaultExtensionRegistry();
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(anyConfig("a", Map.of("k", "v1"), true)),
                new ChangeCommand.RegisterDefinition(anyConfig("b", Map.of("k", "v1"), true)))));
        // 同批次移除 b 并以不同版本重新注册 b
        registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RemoveExtension("b"),
                new ChangeCommand.RegisterDefinition(new ExtensionDefinition(
                        "b", Version.parse("2.0.0"), List.of(), true, Map.of("k", "v2"),
                        AnyConfigExtension::new)))));

        HistoryVersion v2 = registry.historyVersion(2);
        log.info("v2 changes={}", v2.changes());
        // 版本变了 -> REDEFINED；配置也变了 -> CONFIG_CHANGED，两个维度都可见
        assertThat(v2.changes()).extracting(ExtensionChange::kind)
                .containsExactlyInAnyOrder(ExtensionChange.ChangeKind.REDEFINED,
                        ExtensionChange.ChangeKind.CONFIG_CHANGED);
        assertThat(v2.changes()).allSatisfy(c -> assertThat(c.extensionId()).isEqualTo("b"));

        // 再彻底移除 b：diff 出现 REMOVED
        registry.apply(new ChangeSet(List.of(new ChangeCommand.RemoveExtension("b"))));
        HistoryVersion v3 = registry.historyVersion(3);
        assertThat(v3.changes()).singleElement().satisfies(c -> {
            assertThat(c.extensionId()).isEqualTo("b");
            assertThat(c.kind()).isEqualTo(ExtensionChange.ChangeKind.REMOVED);
        });
        assertThat(v3.extensions()).extracting(ExtensionStateSnapshot::id).containsExactly("a");
    }
}
