package com.voyageiq.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.voyageiq.business.service.ConversationService;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:apiint;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class BusinessApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConversationService conversationService;

    @Test
    void loginConversationLifecycleAndSoftDeleteAreProtectedByJwt() throws Exception {
        mvc.perform(get("/api/v1/conversations")).andExpect(status().isUnauthorized());

        String loginBody = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"voyage\",\"password\":\"Voyage@2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("voyage"))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginBody).get("accessToken").asText();

        String createdBody = mvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"季度会议差旅\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("季度会议差旅"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createdBody);
        String id = created.get("id").asText();

        mvc.perform(patch("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"上海季度会议\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("上海季度会议"));

        mvc.perform(delete("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/conversations/{id}/messages", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalApisRejectInvalidServiceIdentity() throws Exception {
        mvc.perform(post("/internal/v1/policies/search")
                        .header("X-Internal-Service-Key", "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"query\":\"住宿标准\",\"limit\":3}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badCredentialsAndInvalidConversationPayloadsAreRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"voyage\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));

        String token = login().get("accessToken").asText();
        mvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "x".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(patch("/api/v1/conversations/missing")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conversationOwnershipIsHiddenAsNotFound() throws Exception {
        JsonNode login = login();
        String id = createConversation(login.get("accessToken").asText(), "仅本人可见").get("id").asText();

        mvc.perform(get("/api/v1/conversations/{id}/messages", id)
                        .with(jwt().jwt(value -> value.subject("different-user")
                                .claim("tenant_id", "different-tenant"))))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/conversations/{id}", id)
                        .with(jwt().jwt(value -> value.subject("different-user")
                                .claim("tenant_id", "different-tenant"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvalCreationIsIdempotentAndStatusIsTenantScoped() throws Exception {
        JsonNode login = login();
        String userId = login.path("user").path("id").asText();
        String key = "integration-" + UUID.randomUUID();
        String body = "{\"userId\":\"" + userId + "\",\"tenantId\":\"tenant-voyage\"," +
                "\"destination\":\"上海\",\"travelDate\":\"2026-09-10\"," +
                "\"budget\":\"1800\",\"reason\":\"客户会议\"}";

        String first = mvc.perform(post("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String approvalNo = objectMapper.readTree(first).get("approvalNo").asText();
        org.junit.jupiter.api.Assertions.assertEquals(
                approvalNo, objectMapper.readTree(second).get("approvalNo").asText());

        String conflictingContext = "{\"userId\":\"different-user\",\"tenantId\":\"other-tenant\"," +
                "\"destination\":\"北京\",\"travelDate\":\"2026-09-11\"," +
                "\"budget\":\"900\",\"reason\":\"不应读取原审批\"}";
        mvc.perform(post("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(conflictingContext))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.message").value("idempotency key belongs to another user or tenant"));

        String visible = mvc.perform(get("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .param("userId", userId).param("tenantId", "tenant-voyage"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(visible.contains(approvalNo));

        mvc.perform(get("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .param("userId", userId).param("tenantId", "other-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void policySearchIsTenantScopedAndLimitIsBounded() throws Exception {
        mvc.perform(post("/internal/v1/policies/search")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"query\":\"住宿 酒店 标准\",\"limit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-voyage"))
                .andExpect(jsonPath("$.items[0].source").exists());

        mvc.perform(post("/internal/v1/policies/search")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"other-tenant\",\"query\":\"住宿 酒店 标准\",\"limit\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void policyEvaluationReturnsVersionedAuditableDecision() throws Exception {
        mvc.perform(post("/internal/v1/policies/evaluate")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"travelDate\":\"2026-09-18\"," +
                                "\"employeeLevel\":\"L1\",\"cityTier\":\"TIER1\"," +
                                "\"travelType\":\"DOMESTIC\",\"budget\":\"9000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(1))
                .andExpect(jsonPath("$.ruleId").isNumber())
                .andExpect(jsonPath("$.overBudget").value(true))
                .andExpect(jsonPath("$.requiresFinance").value(true))
                .andExpect(jsonPath("$.approvalRoute").value("MANAGER_THEN_FINANCE"))
                .andExpect(jsonPath("$.explanation").value(org.hamcrest.Matchers.containsString("命中规则")))
                .andExpect(jsonPath("$.snapshotJson").isString());
    }

    @Test
    void policyAdministrationPublishesTenantVersionAndInvalidatesDecisionCatalog() throws Exception {
        String tenant = "tenant-policy-api-" + UUID.randomUUID();
        mvc.perform(post("/internal/v1/policies/versions")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenant + "\",\"version\":1," +
                                "\"effectiveFrom\":\"2026-01-01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versionNo").value(1));
        mvc.perform(post("/internal/v1/policies/rules")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenant + "\",\"version\":1," +
                                "\"name\":\"API租户规则\",\"employeeLevel\":\"*\"," +
                                "\"cityTier\":\"*\",\"travelType\":\"DOMESTIC\"," +
                                "\"maxBudget\":\"1500\",\"requiresFinance\":false,\"priority\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ruleName").value("API租户规则"));
        mvc.perform(post("/internal/v1/policies/evaluate")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenant + "\",\"travelDate\":\"2026-09-18\"," +
                                "\"travelType\":\"DOMESTIC\",\"budget\":\"1200\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ruleName").value("API租户规则"))
                .andExpect(jsonPath("$.requiresFinance").value(false));

        mvc.perform(post("/internal/v1/policies/versions")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenant + "\",\"version\":2," +
                                "\"effectiveFrom\":\"2027-02-01\",\"effectiveTo\":\"2027-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_VALIDATION_FAILED"));
    }

    @Test
    void approvalTransitionEnforcesRoleAndOptimisticVersionAtHttpBoundary() throws Exception {
        String key = "transition-api-" + UUID.randomUUID();
        String userId = login().path("user").path("id").asText();
        String body = "{\"userId\":\"" + userId + "\",\"tenantId\":\"tenant-voyage\"," +
                "\"destination\":\"上海\",\"travelDate\":\"2026-09-18\"," +
                "\"budget\":\"1800\",\"reason\":\"客户会议\"}";
        JsonNode created = objectMapper.readTree(mvc.perform(post("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String approvalNo = created.get("approvalNo").asText();
        long version = created.get("version").asLong();

        mvc.perform(post("/internal/v1/approvals/{approvalNo}/transitions", approvalNo)
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"action\":\"APPROVE\"," +
                                "\"actorRole\":\"FINANCE\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BUSINESS_FORBIDDEN"));

        JsonNode manager = objectMapper.readTree(mvc.perform(post(
                        "/internal/v1/approvals/{approvalNo}/transitions", approvalNo)
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"action\":\"APPROVE\"," +
                                "\"actorRole\":\"MANAGER\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_FINANCE"))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/internal/v1/approvals/{approvalNo}/transitions", approvalNo)
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"action\":\"APPROVE\"," +
                                "\"actorRole\":\"FINANCE\",\"expectedVersion\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_CONFLICT"));

        mvc.perform(post("/internal/v1/approvals/{approvalNo}/transitions", approvalNo)
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"action\":\"APPROVE\"," +
                                "\"actorRole\":\"FINANCE\",\"expectedVersion\":" +
                                manager.get("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void meReturnsTheJwtBackedUserProfile() throws Exception {
        JsonNode login = login();
        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + login.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("voyage"))
                .andExpect(jsonPath("$.tenantId").value("tenant-voyage"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void blankLoginFieldsAreRejectedByBeanValidation() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingConversationBodyUsesAStableDefaultTitle() throws Exception {
        String token = login().get("accessToken").asText();
        mvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("新对话"))
                .andExpect(jsonPath("$.messageCount").value(0));
    }

    @Test
    void conversationSearchAndPaginationAreBounded() throws Exception {
        String token = login().get("accessToken").asText();
        String marker = "搜索标记-" + UUID.randomUUID();
        createConversation(token, marker);

        mvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .param("query", marker).param("page", "-9").param("size", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].title").value(marker));
    }

    @Test
    void renameChangesTitleAndAdvancesOptimisticVersion() throws Exception {
        String token = login().get("accessToken").asText();
        JsonNode created = createConversation(token, "待重命名");
        long oldVersion = created.get("version").asLong();
        String renamed = mvc.perform(patch("/api/v1/conversations/{id}", created.get("id").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"已重命名\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(objectMapper.readTree(renamed).get("version").asLong() > oldVersion);
    }

    @Test
    void softDeletedConversationIsExcludedFromSearchResults() throws Exception {
        String token = login().get("accessToken").asText();
        String marker = "删除标记-" + UUID.randomUUID();
        String id = createConversation(token, marker).get("id").asText();
        mvc.perform(delete("/api/v1/conversations/{id}", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token).param("query", marker))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void missingInternalServiceKeyIsRejectedBeforeBusinessLogic() throws Exception {
        mvc.perform(post("/internal/v1/policies/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"tenant-voyage\",\"query\":\"住宿标准\",\"limit\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messageHistoryExposesPersistedAgentRuntime() throws Exception {
        JsonNode login = login();
        String token = login.get("accessToken").asText();
        String userId = login.path("user").path("id").asText();
        String id = createConversation(token, "运行记录持久化").get("id").asText();
        String runtime = "[{\"type\":\"plan\",\"data\":{\"summary\":\"执行计划\"}}]";
        conversationService.append(id, userId, "assistant", "response_composer", "已完成",
                "[]", "[]", "trace-runtime", runtime);

        mvc.perform(get("/api/v1/conversations/{id}/messages", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runtimeJson").value(runtime));
    }

    @Test
    void malformedApprovalBudgetIsRejectedAsValidationError() throws Exception {
        mvc.perform(post("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .header("Idempotency-Key", "bad-budget-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"tenantId\":\"tenant-voyage\"," +
                                "\"destination\":\"北京\",\"travelDate\":\"2026-09-18\"," +
                                "\"budget\":\"12x\",\"reason\":\"客户会议\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void incompleteApprovalPayloadIsRejectedBeforePersistence() throws Exception {
        mvc.perform(post("/internal/v1/approvals")
                        .header("X-Internal-Service-Key", "voyageiq-local-internal-key")
                        .header("Idempotency-Key", "missing-fields-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"tenantId\":\"tenant-voyage\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedBearerTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer definitely-not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode login() throws Exception {
        String value = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"voyage\",\"password\":\"Voyage@2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(value);
    }

    private JsonNode createConversation(String token, String title) throws Exception {
        String value = mvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("title", title))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(value);
    }
}
