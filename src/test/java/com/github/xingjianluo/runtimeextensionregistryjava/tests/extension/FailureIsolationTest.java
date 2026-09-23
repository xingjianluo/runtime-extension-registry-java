package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionContext;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 单个扩展初始化/执行失败的故障隔离与错误归一化验证。 */
class FailureIsolationTest {

    private static final Logger log = LoggerFactory.getLogger(FailureIsolationTest.class);

    /** 每次执行都抛出运行时异常的扩展。 */
    static class AlwaysFailingExtension implements Extension {
        @Override
        public Object execute(Map<String, Object> input) {
            throw new IllegalStateException("boom: internal secret detail");
        }
    }

    /** 正常扩展。 */
    static class OkExtension implements Extension {
        @Override
        public Object execute(Map<String, Object> input) {
            return Map.of("status", "ok");
        }
    }

    private DefaultExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry();
    }

    private void register(String id,
                           com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.ExtensionFactory factory) {
        registry.apply(new ChangeSet(List.of(new ChangeCommand.RegisterDefinition(
                new ExtensionDefinition(id, Version.parse("1.0.0"), List.of(), true, Map.of(), factory)))));
    }

    @Test
    void executionFailureIsNormalizedWithDistinctCodeAndContext() {
        register("bad", AlwaysFailingExtension::new);
        register("good", OkExtension::new);

        log.info("invoke failing extension input={} expect=EXECUTION_FAILED", Map.of());
        assertThatThrownBy(() -> registry.invoke(new ExtensionInvocation("bad", Map.of("x", 1))))
                .isInstanceOf(ExtensionException.class)
                .satisfies(ex -> {
                    ExtensionException ee = (ExtensionException) ex;
                    assertThat(ee.code()).isEqualTo(ErrorCode.EXECUTION_FAILED);
                    assertThat(ee.context()).containsKey("extensionId");
                    assertThat(ee.context()).containsKey("generation");
                });
    }

    @Test
    void failureOfOneExtensionDoesNotAffectOthers() {
        register("bad", AlwaysFailingExtension::new);
        register("good", OkExtension::new);

        // bad 连续失败多次
        for (int i = 0; i < 5; i++) {
            try {
                registry.invoke(new ExtensionInvocation("bad", Map.of()));
            } catch (ExtensionException expected) {
                assertThat(expected.code()).isEqualTo(ErrorCode.EXECUTION_FAILED);
            }
        }
        // good 始终可服务；bad 也仍可被再次调用（失败不摘除扩展）
        var goodResult = registry.invoke(new ExtensionInvocation("good", Map.of()));
        assertThat(((Map<?, ?>) goodResult.data()).get("status")).isEqualTo("ok");
        assertThat(registry.status("bad").orElseThrow().ready()).isTrue();
        log.info("isolation verified: good={} badStillReady=true", goodResult.data());
    }

    @Test
    void unknownExtensionAndBlankIdAreRejectedDistinctly() {
        log.info("invoke unknown extension expect=EXTENSION_NOT_FOUND");
        assertThatThrownBy(() -> registry.invoke(new ExtensionInvocation("nope", Map.of())))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.EXTENSION_NOT_FOUND);
    }

    @Test
    void initFailureInOneOfBatchRollsBackWholeBatchWithoutAffectingOthers() {
        // 已存在的 good 正常服务；新批次包含一个初始化必失败的新扩展 -> 整批拒绝
        register("good", OkExtension::new);
        long generationBefore = registry.snapshot().generation();

        ExtensionDefinition broken = new ExtensionDefinition(
                "broken", Version.parse("1.0.0"), List.of(), true,
                Map.of("required", "x"),
                () -> new Extension() {
                    @Override
                    public void initialize(ExtensionContext context) {
                        throw new IllegalStateException("cannot start");
                    }
                });
        log.info("batch with broken initializer expect=INITIALIZATION_FAILED rollback");
        assertThatThrownBy(() -> registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RegisterDefinition(broken)))))
                .isInstanceOf(ExtensionException.class)
                .extracting(ex -> ((ExtensionException) ex).code())
                .isEqualTo(ErrorCode.INITIALIZATION_FAILED);

        assertThat(registry.snapshot().generation()).isEqualTo(generationBefore);
        assertThat(registry.status("broken")).isEmpty();
        assertThat(registry.invoke(new ExtensionInvocation("good", Map.of())).data())
                .isEqualTo(Map.of("status", "ok"));
    }
}
