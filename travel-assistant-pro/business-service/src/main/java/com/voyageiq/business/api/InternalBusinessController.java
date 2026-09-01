package com.voyageiq.business.api;

import com.voyageiq.business.config.VoyageIqProperties;
import com.voyageiq.business.domain.ApprovalAction;
import com.voyageiq.business.domain.PolicyDocument;
import com.voyageiq.business.domain.PolicyVersion;
import com.voyageiq.business.domain.TravelApproval;
import com.voyageiq.business.domain.TravelPolicyRule;
import com.voyageiq.business.repository.PolicyDocumentRepository;
import com.voyageiq.business.repository.TravelApprovalRepository;
import com.voyageiq.business.service.ApprovalCommandService;
import com.voyageiq.business.service.ApprovalTransactionService;
import com.voyageiq.business.service.ApprovalWorkflowService;
import com.voyageiq.business.service.PolicyAdministrationService;
import com.voyageiq.business.service.PolicyDecisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1")
public class InternalBusinessController {
    private final VoyageIqProperties properties;
    private final PolicyDocumentRepository policies;
    private final TravelApprovalRepository approvals;
    private final ApprovalCommandService approvalCommands;
    private final ApprovalWorkflowService workflows;
    private final PolicyDecisionService decisions;
    private final PolicyAdministrationService policyAdmin;

    public InternalBusinessController(VoyageIqProperties properties, PolicyDocumentRepository policies,
                                      TravelApprovalRepository approvals, ApprovalCommandService approvalCommands,
                                      ApprovalWorkflowService workflows, PolicyDecisionService decisions,
                                      PolicyAdministrationService policyAdmin) {
        this.properties = properties;
        this.policies = policies;
        this.approvals = approvals;
        this.approvalCommands = approvalCommands;
        this.workflows = workflows;
        this.decisions = decisions;
        this.policyAdmin = policyAdmin;
    }

    @PostMapping("/policies/search")
    @Transactional(readOnly = true)
    public Map<String, Object> searchPolicy(@RequestHeader("X-Internal-Service-Key") String key,
                                            @Valid @RequestBody PolicySearchRequest request) {
        verify(key);
        List<Map<String, Object>> items = policies.findByTenantIdAndEnabledTrue(request.tenantId()).stream()
                .map(policy -> Map.entry(policy, score(policy, request.query())))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<PolicyDocument, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.min(Math.max(request.limit(), 1), 10))
                .map(entry -> Map.<String, Object>of(
                        "id", entry.getKey().getId(), "title", entry.getKey().getTitle(),
                        "content", entry.getKey().getContent(), "source", entry.getKey().getSource(),
                        "score", entry.getValue()))
                .toList();
        return Map.of("items", items, "total", items.size(), "tenantId", request.tenantId());
    }

    @PostMapping("/policies/evaluate")
    @Transactional(readOnly = true)
    public PolicyDecisionService.Decision evaluatePolicy(
            @RequestHeader("X-Internal-Service-Key") String key,
            @Valid @RequestBody PolicyEvaluateRequest request) {
        verify(key);
        return decisions.decide(new PolicyDecisionService.DecisionRequest(request.tenantId(),
                LocalDate.parse(request.travelDate()), normalize(request.employeeLevel(), "L1"),
                normalize(request.cityTier(), "OTHER"), normalize(request.travelType(), "DOMESTIC"),
                decimal(request.budget())));
    }

    @PostMapping("/policies/versions")
    public PolicyVersion publishPolicyVersion(@RequestHeader("X-Internal-Service-Key") String key,
                                              @Valid @RequestBody PolicyVersionRequest request) {
        verify(key);
        return policyAdmin.publishVersion(request.tenantId(), request.version(),
                LocalDate.parse(request.effectiveFrom()), blank(request.effectiveTo()) ? null
                        : LocalDate.parse(request.effectiveTo()));
    }

    @PostMapping("/policies/rules")
    public TravelPolicyRule addPolicyRule(@RequestHeader("X-Internal-Service-Key") String key,
                                          @Valid @RequestBody PolicyRuleRequest request) {
        verify(key);
        return policyAdmin.addRule(request.tenantId(), request.version(), request.name(),
                request.employeeLevel(), request.cityTier(), request.travelType(),
                new BigDecimal(request.maxBudget()), request.requiresFinance(), request.priority());
    }

    @PostMapping("/approvals")
    public ApprovalView createApproval(@RequestHeader("X-Internal-Service-Key") String key,
                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @Valid @RequestBody ApprovalCreateRequest request) {
        verify(key);
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid idempotency key");
        }
        ApprovalCommandService.SubmissionResult result = approvalCommands.submit(
                new ApprovalTransactionService.ApprovalSubmission(idempotencyKey, request.userId(),
                        request.tenantId(), request.destination(), LocalDate.parse(request.travelDate()),
                        decimal(request.budget()), request.reason(), normalize(request.employeeLevel(), "L1"),
                        normalize(request.cityTier(), "OTHER"), normalize(request.travelType(), "DOMESTIC")));
        return ApprovalView.from(result.approval(), result.replayed());
    }

    @PostMapping("/approvals/{approvalNo}/transitions")
    public ApprovalView transition(@RequestHeader("X-Internal-Service-Key") String key,
                                   @PathVariable String approvalNo,
                                   @Valid @RequestBody ApprovalTransitionRequest request) {
        verify(key);
        TravelApproval value = workflows.transition(request.tenantId(), approvalNo,
                ApprovalAction.valueOf(request.action().toUpperCase()), request.actorRole(),
                request.expectedVersion());
        return ApprovalView.from(value, false);
    }

    @GetMapping("/approvals")
    @Transactional(readOnly = true)
    public Map<String, Object> approvals(@RequestHeader("X-Internal-Service-Key") String key,
                                        @RequestParam String userId, @RequestParam String tenantId) {
        verify(key);
        List<ApprovalView> items = approvals.findByUserIdAndTenantIdOrderByCreatedAtDesc(userId, tenantId)
                .stream().map(value -> ApprovalView.from(value, false)).toList();
        return Map.of("items", items, "total", items.size());
    }

    private int score(PolicyDocument policy, String query) {
        int value = 0;
        for (String token : query.split("[\\s，。！？、]+")) {
            if (token.length() < 2) continue;
            if (policy.getKeywords().contains(token)) value += 3;
            if (policy.getContent().contains(token)) value += 1;
        }
        for (String keyword : policy.getKeywords().split(" ")) if (query.contains(keyword)) value += 3;
        return value;
    }

    private void verify(String actual) {
        byte[] left = actual.getBytes(StandardCharsets.UTF_8);
        byte[] right = properties.security().internalServiceKey().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(left, right)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal service identity");
        }
    }

    private static BigDecimal decimal(String value) { return blank(value) ? null : new BigDecimal(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value, String fallback) {
        return blank(value) ? fallback : value.trim().toUpperCase();
    }

    public record PolicySearchRequest(@NotBlank String tenantId, @NotBlank String query, int limit) {}
    public record PolicyEvaluateRequest(@NotBlank String tenantId,
                                        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String travelDate,
                                        String employeeLevel, String cityTier, String travelType,
                                        @Pattern(regexp = "\\d{1,9}(?:\\.\\d{1,2})?") String budget) {}
    public record PolicyVersionRequest(@NotBlank String tenantId, @Min(1) int version,
                                       @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String effectiveFrom,
                                       String effectiveTo) {}
    public record PolicyRuleRequest(@NotBlank String tenantId, @Min(1) int version, @NotBlank String name,
                                    String employeeLevel, String cityTier, String travelType,
                                    @NotBlank @Pattern(regexp = "\\d{1,9}(?:\\.\\d{1,2})?") String maxBudget,
                                    boolean requiresFinance, @Min(0) int priority) {}
    public record ApprovalCreateRequest(@NotBlank String userId, @NotBlank String tenantId,
                                        @NotBlank String destination,
                                        @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String travelDate,
                                        @Pattern(regexp = "\\d{1,9}(?:\\.\\d{1,2})?",
                                                message = "must be a positive decimal with at most 2 fraction digits")
                                        String budget, @NotBlank String reason,
                                        String employeeLevel, String cityTier, String travelType) {}
    public record ApprovalTransitionRequest(@NotBlank String tenantId, @NotBlank String action,
                                            @NotBlank String actorRole, @Min(0) long expectedVersion) {}

    public record ApprovalView(String approvalNo, String destination, String travelDate, BigDecimal budget,
                               String reason, String status, String employeeLevel, String cityTier,
                               String travelType, Integer policyVersion, Long policyRuleId,
                               String policySnapshot, String decisionTrace, boolean requiresFinance,
                               long version, boolean idempotentReplay, java.time.Instant createdAt) {
        static ApprovalView from(TravelApproval value, boolean replayed) {
            return new ApprovalView(value.getApprovalNo(), value.getDestination(), value.getTravelDate(),
                    value.getBudget(), value.getReason(), value.getStatus(), value.getEmployeeLevel(),
                    value.getCityTier(), value.getTravelType(), value.getPolicyVersion(), value.getPolicyRuleId(),
                    value.getPolicySnapshot(), value.getDecisionTrace(), value.isRequiresFinance(),
                    value.getVersion(), replayed, value.getCreatedAt());
        }
    }
}
