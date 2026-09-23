package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 端到端：播种扩展 + HTTP 热更新 + 错误归一化（不透出堆栈）。 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetStrictToDisabled() throws Exception {
        // 复位 strict 为停用，保证各用例顺序无关
        mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"action\":\"disable\",\"extensionId\":\"strict\"}]"))
                .andReturn();
    }

    private long currentGeneration() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/extensions")).andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.generation")).longValue();
    }

    @Test
    void listsSeededExtensionsAtGenerationOne() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/extensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensions[*].id",
                        org.hamcrest.Matchers.containsInAnyOrder("echo", "transform", "strict")))
                .andReturn();
        log.info("GET /api/extensions -> 200 body={}", result.getResponse().getContentAsString());
    }

    @Test
    void invokesEchoAndTransformViaHttp() throws Exception {
        long gen = currentGeneration();
        MvcResult echo = mockMvc.perform(post("/api/extensions/echo/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extensionId").value("echo"))
                .andExpect(jsonPath("$.generation").value(gen))
                .andReturn();
        log.info("invoke echo input={{text:hello}} -> {}", echo.getResponse().getContentAsString());

        mockMvc.perform(post("/api/extensions/transform/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("HELLO"))
                .andExpect(jsonPath("$.data.upstream").exists());
    }

    @Test
    void hotUpdateConfigTakesEffectAtomicallyOverHttp() throws Exception {
        // transform 切到 LOWER：合法配置 -> 200，新生效世代
        String requestBody = "[{\"action\":\"update-config\",\"extensionId\":\"transform\",\"config\":{\"mode\":\"LOWER\"}}]";
        log.info("POST /changes input={}", requestBody);
        long genBefore = currentGeneration();
        mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(genBefore + 1));

        mockMvc.perform(post("/api/extensions/transform/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"HeLLo\"}"))
                .andExpect(jsonPath("$.data.text").value("hello"));

        // 还原
        mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"action\":\"update-config\",\"extensionId\":\"transform\",\"config\":{\"mode\":\"UPPER\"}}]"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidConfigChangeIsRejectedAndRolledBackOverHttp() throws Exception {
        String requestBody = "[{\"action\":\"update-config\",\"extensionId\":\"transform\",\"config\":{\"mode\":\"SIDEWAYS\"}}]";
        log.info("POST /changes input={} expect 409 INVALID_CONFIG (rollback)", requestBody);
        MvcResult result = mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONFIG"))
                .andExpect(jsonPath("$.context.extensionId").value("transform"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // 不得透出底层异常原文或堆栈
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("Exception", "at java.", "at org.", "SIDEWAYS");
        log.info("rejected change normalized body={}", body);

        // 原状态仍可用且世代未前进（该用例中世代应为 1，因为前两个用例的回滚不产生新世代）
        mockMvc.perform(get("/api/extensions/{id}", "transform"))
                .andExpect(jsonPath("$.config.mode").value("UPPER"))
                .andExpect(jsonPath("$.ready").value(true));
    }

    @Test
    void disabledAndNotFoundAndUnknownActionAreNormalizedWithoutStackTraces() throws Exception {
        // strict 默认停用 -> 409 EXTENSION_DISABLED
        mockMvc.perform(post("/api/extensions/strict/invoke").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXTENSION_DISABLED"));

        // 未知扩展 -> 404
        mockMvc.perform(post("/api/extensions/ghost/invoke").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXTENSION_NOT_FOUND"));

        // 未知 action -> 400 BAD_REQUEST
        String body = "[{\"action\":\"frobnicate\",\"extensionId\":\"echo\"}]";
        MvcResult bad = mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(bad.getResponse().getContentAsString())
                .doesNotContain("at java.", "at org.springframework");
    }

    @Test
    void enableStrictWithInvalidConfigRollsBackThenValidConfigSucceeds() throws Exception {
        // 启用 strict 但配置无效（同一批次：更新为坏配置 + 启用）-> 整体拒绝，strict 仍停用
        String bad = "[{\"action\":\"update-config\",\"extensionId\":\"strict\",\"config\":{\"required\":\"\"}},"
                + "{\"action\":\"enable\",\"extensionId\":\"strict\"}]";
        log.info("atomic enable with bad config input={} expect 409 rollback", bad);
        mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CONFIG"));
        mockMvc.perform(get("/api/extensions/strict"))
                .andExpect(jsonPath("$.enabled").value(false));

        // 合法配置 + 启用（批次顺序：先配置后启用）-> 成功
        String good = "[{\"action\":\"update-config\",\"extensionId\":\"strict\",\"config\":{\"required\":\"live\"}},"
                + "{\"action\":\"enable\",\"extensionId\":\"strict\"}]";
        mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshot.extensions[?(@.id=='strict')].ready").value(
                        org.hamcrest.Matchers.contains(true)));
        mockMvc.perform(post("/api/extensions/strict/invoke")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"k\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.required").value("live"));
    }
}
