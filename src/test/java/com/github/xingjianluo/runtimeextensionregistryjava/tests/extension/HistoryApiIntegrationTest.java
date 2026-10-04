package com.github.xingjianluo.runtimeextensionregistryjava.tests.extension;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 端到端：历史版本查询、按号回退、查不到/已淘汰的可区分 404。 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HistoryApiIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    private List<Integer> historyNumbers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/extensions/history"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$[*].number");
    }

    @Test
    void historyListsVersionsWithChangesAndState() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/extensions/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].number").exists())
                .andExpect(jsonPath("$[0].changes").isArray())
                .andExpect(jsonPath("$[0].extensions").isArray())
                .andReturn();
        log.info("GET /api/extensions/history -> {}", result.getResponse().getContentAsString());
    }

    @Test
    void rollbackOverHttpRestoresStateAndAppendsNewVersion() throws Exception {
        // 记录变更前最新历史编号与 transform 当前配置
        List<Integer> before = historyNumbers();
        long target = before.get(before.size() - 1);
        MvcResult beforeState = mockMvc.perform(get("/api/extensions/transform")).andReturn();
        String modeBefore = JsonPath.read(beforeState.getResponse().getContentAsString(), "$.config.mode");

        // 提交一次配置变更 -> 产生新历史版本
        String newMode = "LOWER".equals(modeBefore) ? "UPPER" : "LOWER";
        String changeBody = "[{\"action\":\"update-config\",\"extensionId\":\"transform\","
                + "\"config\":{\"mode\":\"" + newMode + "\"}}]";
        log.info("rollback-http scenario: modeBefore={} changeInput={} rollbackTarget={}",
                modeBefore, changeBody, target);
        mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(changeBody))
                .andExpect(status().isOk());

        // 变更后的版本可按号查询，diff 可见 CONFIG_CHANGED
        List<Integer> after = historyNumbers();
        long newVersion = after.get(after.size() - 1);
        assertThat(newVersion).isGreaterThan(target);
        MvcResult detail = mockMvc.perform(get("/api/extensions/history/" + newVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value((int) newVersion))
                .andReturn();
        log.info("GET /history/{} -> {}", newVersion, detail.getResponse().getContentAsString());
        List<Map<String, Object>> changes = JsonPath.read(
                detail.getResponse().getContentAsString(), "$.changes[?(@.extensionId=='transform')]");
        assertThat(changes).anySatisfy(c -> assertThat(c.get("kind")).isEqualTo("CONFIG_CHANGED"));

        // 回退到变更前版本 -> 200，transform 配置恢复，且追加了更新编号的历史版本
        MvcResult rollback = mockMvc.perform(post("/api/extensions/history/" + target + "/rollback"))
                .andExpect(status().isOk())
                .andReturn();
        log.info("POST /history/{}/rollback -> {}", target, rollback.getResponse().getContentAsString());
        MvcResult restored = mockMvc.perform(get("/api/extensions/transform")).andReturn();
        String modeAfterRollback = JsonPath.read(
                restored.getResponse().getContentAsString(), "$.config.mode");
        assertThat(modeAfterRollback).isEqualTo(modeBefore);

        List<Integer> finalNumbers = historyNumbers();
        assertThat(finalNumbers.get(finalNumbers.size() - 1)).isGreaterThan((int) newVersion);
        // 原历史未被覆盖：变更产生的版本仍然可查
        mockMvc.perform(get("/api/extensions/history/" + newVersion))
                .andExpect(status().isOk());
    }

    @Test
    void missingHistoryVersionIsDistinguishableNotFound() throws Exception {
        log.info("query/rollback never-existed version=999999 expect=404 VERSION_NOT_FOUND");
        MvcResult query = mockMvc.perform(get("/api/extensions/history/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"))
                .andReturn();
        log.info("GET /history/999999 -> {}", query.getResponse().getContentAsString());

        MvcResult rollback = mockMvc.perform(post("/api/extensions/history/999999/rollback"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"))
                .andReturn();
        log.info("POST /history/999999/rollback -> {}", rollback.getResponse().getContentAsString());
    }
}
