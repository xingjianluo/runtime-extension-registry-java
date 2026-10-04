package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeCommand;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ChangeSet;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.ExtensionDefinition;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.model.Version;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.registry.DefaultExtensionRegistry;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.spi.Extension;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.web.ApiExceptionHandler;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.web.ExtensionController;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 历史版本与回退的 HTTP 端到端验证（standalone MockMvc，注册中心全新隔离）：
 * 历史列表/详情、淘汰或不存在版本 404、回退成功产生新编号、回退被拒 409 且错误码可区分。
 */
class HistoryRollbackWebApiTest {

    private static final Logger log = LoggerFactory.getLogger(HistoryRollbackWebApiTest.class);

    static class NoopExtension implements Extension {
    }

    private MockMvc mockMvc;
    private DefaultExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultExtensionRegistry(3); // 小保留上限，便于验证淘汰 404
        ExtensionDefinition a = new ExtensionDefinition("a", Version.parse("1.0.0"), List.of(),
                true, Map.of("v", "a1"), NoopExtension::new);
        registry.apply(new ChangeSet(List.of(new ChangeCommand.RegisterDefinition(a))));
        mockMvc = MockMvcBuilders.standaloneSetup(new ExtensionController(registry))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private long changeConfig(String value) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"action\":\"update-config\",\"extensionId\":\"a\",\"config\":{\"v\":\""
                                + value + "\"}}]"))
                .andExpect(status().isOk())
                .andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.generation")).longValue();
    }

    @Test
    void listsHistoryAndFetchesVersionWithDiff() throws Exception {
        long v2 = changeConfig("a2");
        long v3 = changeConfig("a3");
        log.info("scenario=http-history committed v2={} v3={}, GET /history", v2, v3);

        mockMvc.perform(get("/api/extensions/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionLimit").value(3))
                .andExpect(jsonPath("$.retainedCount").value(3))
                // 从新到旧
                .andExpect(jsonPath("$.versions[0].version").value(v3))
                .andExpect(jsonPath("$.versions[0].kind").value("CHANGE"))
                .andExpect(jsonPath("$.versions[0].changedIds[0]").value("a"))
                .andExpect(jsonPath("$.versions[2].version").value(1));

        mockMvc.perform(get("/api/extensions/history/{version}", v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v2))
                .andExpect(jsonPath("$.kind").value("CHANGE"))
                .andExpect(jsonPath("$.diff.baseVersion").value(v2 - 1))
                .andExpect(jsonPath("$.diff.changes[0].extensionId").value("a"))
                .andExpect(jsonPath("$.diff.changes[0].type").value("CONFIG_CHANGED"))
                .andExpect(jsonPath("$.snapshot.extensions[0].config.v").value("a2"));
    }

    @Test
    void unknownOrEvictedVersionReturns404() throws Exception {
        long v1 = 1L;
        long v2 = changeConfig("a2");
        long v3 = changeConfig("a3");
        long v4 = changeConfig("a4");
        long v5 = changeConfig("a5"); // 上限 3：5 个版本后保留 {v3,v4,v5}，v1、v2 均被淘汰
        log.info("scenario=http-eviction versions=1..{} retention=3, expect v1/v2 404", v5);

        mockMvc.perform(get("/api/extensions/history/{version}", v1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HISTORY_VERSION_NOT_FOUND"))
                .andExpect(jsonPath("$.context.version").value(1));

        // 从未存在的编号
        MvcResult missing = mockMvc.perform(get("/api/extensions/history/{version}", 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HISTORY_VERSION_NOT_FOUND"))
                .andReturn();
        log.info("scenario=http-missing-version body={}", missing.getResponse().getContentAsString());

        // 回退到已淘汰版本同样是 404（查不到），而非 409
        mockMvc.perform(post("/api/extensions/history/{version}/rollback", v2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HISTORY_VERSION_NOT_FOUND"));

        // 仍保留的版本可正常查到
        mockMvc.perform(get("/api/extensions/history/{version}", v3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v3));
    }

    @Test
    void rollbackSucceedsAppendsNewVersionAndRestoresConfig() throws Exception {
        long v2 = changeConfig("a2");
        long v3 = changeConfig("a3");
        log.info("scenario=http-rollback rollback->v{} from v{}", v2, v3);

        mockMvc.perform(post("/api/extensions/history/{version}/rollback", v2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(v3 + 1))
                .andExpect(jsonPath("$.snapshot.extensions[0].config.v").value("a2"));

        // 当前状态已是 a2；原历史不被覆盖
        mockMvc.perform(get("/api/extensions/history/{version}", v3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.extensions[0].config.v").value("a3"));

        mockMvc.perform(get("/api/extensions/history/{version}", v3 + 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ROLLBACK"))
                .andExpect(jsonPath("$.revertedFrom").value(v2));
    }

    @Test
    void rollbackTargetWithRemovedExtensionIsRejected409() throws Exception {
        long v2 = changeConfig("a2");
        // 注册第二个扩展 b，再移除它：回退到 b 存在之前不可能，这里改为构造"目标含已移除扩展"
        ExtensionDefinition b = new ExtensionDefinition("b", Version.parse("1.0.0"), List.of(),
                true, Map.of(), NoopExtension::new);
        registry.apply(new ChangeSet(List.of(new ChangeCommand.RegisterDefinition(b)))); // v3 含 b
        long v4 = registry.apply(new ChangeSet(List.of(
                new ChangeCommand.RemoveExtension("b")))).generation();
        log.info("scenario=http-rollback-rejected target v3 contains removed b, current v{}", v4);

        MvcResult result = mockMvc.perform(post("/api/extensions/history/{version}/rollback", v2 + 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLLBACK_NOT_POSSIBLE"))
                .andExpect(jsonPath("$.context.missingInCurrent[0]").value("b"))
                .andReturn();
        log.info("scenario=http-rollback-rejected body={}", result.getResponse().getContentAsString());

        // 当前状态原样可用：世代停在 v4
        mockMvc.perform(get("/api/extensions"))
                .andExpect(jsonPath("$.generation").value(v4));
    }
}
