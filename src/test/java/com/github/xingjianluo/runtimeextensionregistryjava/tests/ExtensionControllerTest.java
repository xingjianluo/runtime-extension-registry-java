package com.github.xingjianluo.runtimeextensionregistryjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end REST verification including distinguishable errors and no stack-trace leakage. */
@SpringBootTest
@AutoConfigureMockMvc
class ExtensionControllerTest {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionControllerTest.class);
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private void register(String body, int expectedStatus) throws Exception {
        LOG.info("[input POST /api/extensions body={}] expectedStatus={}", body, expectedStatus);
        mockMvc.perform(post("/api/extensions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    private String change(String body, int expectedStatus) throws Exception {
        LOG.info("[input POST /api/extensions/changes body={}] expectedStatus={}", body, expectedStatus);
        String response = mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        LOG.info("[decision response={}]", response);
        return response;
    }

    @Test
    void fullLifecycleRegisterEnableExecuteUpdateDisable() throws Exception {
        register("""
                {"id":"ce-echo","version":"1.0.0","kind":"echo","config":{"prefix":"hello-"}}
                """, 201);

        // Enabled -> execute works.
        change("""
                {"items":[{"extensionId":"ce-echo","enabled":true}]}
                """, 200);
        mockMvc.perform(post("/api/extensions/ce-echo/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"world\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.echo").value("hello-world"));

        // Hot update config then execute reflects new prefix.
        change("""
                {"items":[{"extensionId":"ce-echo","enabled":true,"config":{"prefix":"bye-"}}]}
                """, 200);
        mockMvc.perform(post("/api/extensions/ce-echo/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"now\"}}"))
                .andExpect(jsonPath("$.result.echo").value("bye-now"));

        // Disable -> execution is rejected with distinguishable code.
        change("""
                {"items":[{"extensionId":"ce-echo","enabled":false}]}
                """, 200);
        mockMvc.perform(post("/api/extensions/ce-echo/execute")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.errorCode").value("EXTENSION_NOT_READY"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void duplicateRegistrationReturnsConflictAndDistinctCode() throws Exception {
        register("""
                {"id":"ce-dup","version":"1.0.0","kind":"echo","config":{"prefix":"a-"}}
                """, 201);
        String response = mockMvc.perform(post("/api/extensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ce-dup","version":"2.0.0","kind":"echo","config":{"prefix":"b-"}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_EXTENSION_ID"))
                .andReturn().getResponse().getContentAsString();
        LOG.info("[decision duplicate registration] response={}", response);

        // Original remains intact.
        mockMvc.perform(get("/api/extensions/ce-dup"))
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.config.prefix").value("a-"));
    }

    @Test
    void unknownKindAndInvalidVersionAreRejectedWithDistinctCodes() throws Exception {
        mockMvc.perform(post("/api/extensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ce-unknown","version":"1.0.0","kind":"ghost","config":{}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_EXTENSION_KIND"));

        String response = mockMvc.perform(post("/api/extensions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id":"ce-badver","version":"not-a-version","kind":"echo","config":{"prefix":"p-"}}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_VERSION"))
                .andReturn().getResponse().getContentAsString();
        LOG.info("[decision invalid version] response={}", response);
    }

    @Test
    void dependencyFlowRejectsNotReadyAndAcceptsAtomicBatch() throws Exception {
        register("""
                {"id":"ce-base","version":"1.2.0","kind":"echo","config":{"prefix":"base-"}}
                """, 201);
        register("""
                {"id":"ce-relay","version":"1.0.0","kind":"relay",
                 "dependencies":[{"extensionId":"ce-base","versionRange":"[1.0.0,2.0.0)"}],"config":{}}
                """, 201);

        // Enabling relay alone fails: dependency not ready.
        String rejected = change("""
                {"items":[{"extensionId":"ce-relay","enabled":true}]}
                """, 422);
        JsonNode node = json.readTree(rejected);
        LOG.info("[decision relay-alone] errorCode={} context={}",
                node.path("errorCode").asText(), node.path("context"));
        org.junit.jupiter.api.Assertions.assertEquals("DEPENDENCY_NOT_READY",
                node.path("errorCode").asText());

        // Atomic batch enabling both succeeds and relay forwards execution.
        change("""
                {"items":[{"extensionId":"ce-base","enabled":true},
                          {"extensionId":"ce-relay","enabled":true}]}
                """, 200);
        mockMvc.perform(post("/api/extensions/ce-relay/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"z\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.target").value("ce-base"))
                .andExpect(jsonPath("$.result.downstream.echo").value("base-z"));
    }

    @Test
    void failedInitializationRollsBackAndFaultIsNotLeaked() throws Exception {
        register("""
                {"id":"ce-faulty","version":"1.0.0","kind":"faulty",
                 "config":{"failOnInit":"true","failOnExecute":"false"}}
                """, 201);
        String response = mockMvc.perform(post("/api/extensions/changes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"extensionId":"ce-faulty","enabled":true}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INITIALIZATION_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("simulated"))))
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        LOG.info("[decision faulty init] response={}", response);
        mockMvc.perform(get("/api/extensions/ce-faulty"))
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }
}
