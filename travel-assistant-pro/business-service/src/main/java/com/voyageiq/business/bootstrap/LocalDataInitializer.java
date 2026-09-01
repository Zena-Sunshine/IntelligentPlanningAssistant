package com.voyageiq.business.bootstrap;

import com.voyageiq.business.domain.PolicyDocument;
import com.voyageiq.business.domain.UserAccount;
import com.voyageiq.business.domain.PolicyVersion;
import com.voyageiq.business.domain.TravelPolicyRule;
import com.voyageiq.business.repository.PolicyDocumentRepository;
import com.voyageiq.business.repository.PolicyVersionRepository;
import com.voyageiq.business.repository.TravelPolicyRuleRepository;
import com.voyageiq.business.repository.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LocalDataInitializer implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PolicyDocumentRepository policies;
    private final PasswordEncoder passwordEncoder;
    private final PolicyVersionRepository policyVersions;
    private final TravelPolicyRuleRepository policyRules;

    public LocalDataInitializer(UserAccountRepository users, PolicyDocumentRepository policies,
                                PasswordEncoder passwordEncoder, PolicyVersionRepository policyVersions,
                                TravelPolicyRuleRepository policyRules) {
        this.users = users;
        this.policies = policies;
        this.passwordEncoder = passwordEncoder;
        this.policyVersions = policyVersions;
        this.policyRules = policyRules;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        users.findByUsernameIgnoreCase("voyage").orElseGet(() -> users.save(new UserAccount(
                UUID.randomUUID().toString(), "voyage", passwordEncoder.encode("Voyage@2026"),
                "差旅运营专员", "tenant-voyage", "USER", true, Instant.now())));
        if (policies.count() == 0) {
            policies.save(new PolicyDocument("tenant-voyage", "境内住宿差旅标准",
                    "一线城市住宿上限为每晚 400 元，其他城市为每晚 350 元；超标需要直属负责人二次审批。",
                    "住宿 酒店 差标 标准 报销", "企业差旅管理制度 2026 版"));
            policies.save(new PolicyDocument("tenant-voyage", "境内交通差旅标准",
                    "国内航班限经济舱，高铁限二等座。因业务需要临时改签，应在报销时关联原出差申请。",
                    "机票 航班 高铁 交通 改签 报销", "企业差旅管理制度 2026 版"));
            policies.save(new PolicyDocument("tenant-voyage", "差旅报销材料清单",
                    "报销需提供电子行程单、住宿发票、审批单和费用明细；出差结束后 10 个工作日内提交。",
                    "报销 材料 发票 行程单 审批", "财务共享中心报销指引"));
        }
        policyVersions.findByTenantIdAndVersionNo("tenant-voyage", 1).orElseGet(() ->
                policyVersions.save(new PolicyVersion("tenant-voyage", 1,
                        LocalDate.of(2026, 1, 1), null)));
        if (policyRules.countByTenantIdAndPolicyVersion("tenant-voyage", 1) == 0) {
            policyRules.save(new TravelPolicyRule("tenant-voyage", 1, "高职级一线城市标准",
                    "L3", "TIER1", "DOMESTIC", new BigDecimal("3000"), false, 300));
            policyRules.save(new TravelPolicyRule("tenant-voyage", 1, "普通员工一线城市标准",
                    "L1", "TIER1", "DOMESTIC", new BigDecimal("1800"), false, 250));
            policyRules.save(new TravelPolicyRule("tenant-voyage", 1, "普通员工其他城市标准",
                    "L1", "OTHER", "DOMESTIC", new BigDecimal("1200"), false, 240));
            policyRules.save(new TravelPolicyRule("tenant-voyage", 1, "境外差旅财务复核",
                    null, null, "INTERNATIONAL", new BigDecimal("8000"), true, 200));
            policyRules.save(new TravelPolicyRule("tenant-voyage", 1, "境内差旅兜底标准",
                    null, null, "DOMESTIC", new BigDecimal("1000"), false, 10));
        }
    }
}
