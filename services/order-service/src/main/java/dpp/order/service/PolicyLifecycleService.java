package dpp.order.service;

import dpp.common.security.CustomerId;
import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.dto.*;
import dpp.order.entity.*;
import dpp.order.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyLifecycleService {

    private final PolicyRepository policyRepository;
    private final ExposureSegmentRepository segmentRepository;
    private final PolicyDocumentRepository documentRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public PolicyLifecycleService(PolicyRepository policyRepository, ExposureSegmentRepository segmentRepository,
                                   PolicyDocumentRepository documentRepository, OutboxPublisher outboxPublisher) {
        this.policyRepository = policyRepository;
        this.segmentRepository = segmentRepository;
        this.documentRepository = documentRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public PolicyResponse endorse(UUID policyId, EndorsementRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime eff = request.getEffectiveDate();
        if (eff.isBefore(policy.getPolicyEffectiveDate()) || eff.isAfter(policy.getPolicyExpirationDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }

        List<ExposureSegment> segments = segmentRepository.findByPolicyIdOrderByExposureSegmentSeqAsc(policyId);
        int nextSeq = segments.isEmpty() ? 0 : segments.get(segments.size() - 1).getExposureSegmentSeq() + 1;

        ExposureSegment seg = new ExposureSegment();
        seg.setSegmentId(UUID.randomUUID());
        seg.setPolicyId(policyId);
        seg.setExposureSegmentSeq(nextSeq);
        seg.setSegmentStart(eff);
        seg.setSegmentEnd(policy.getPolicyExpirationDate());
        long days = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        seg.setEarnedExposureYears(days / 365.25);
        seg.setCoverageAmountVnd(0);
        seg.setDeductibleVnd(0);
        try {
            seg.setRiskSnapshot(objectMapper.writeValueAsString(request.getChange()));
        } catch (Exception e) {
            seg.setRiskSnapshot("{}");
        }
        segmentRepository.save(seg);

        int newVersion = documentRepository.findByPolicyIdOrderByVersionDesc(policyId).stream()
                .findFirst().map(PolicyDocument::getVersion).orElse(1) + 1;
        PolicyDocument doc = new PolicyDocument();
        doc.setDocumentId(UUID.randomUUID());
        doc.setPolicyId(policyId);
        doc.setVersion(newVersion);
        doc.setContent("{}");
        doc.setCreatedAt(OffsetDateTime.now());
        documentRepository.save(doc);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(eff, policy.getPolicyExpirationDate());
        enqueueEvent("EndorsementApplied", policyId, Map.of("customer_id", policy.getCustomerId().toString(), "premium_old", policy.getFinalPremiumVnd(), "premium_new", policy.getFinalPremiumVnd(), "remaining_days", remainingDays, "term_days", termDays));
        return toResponse(policy);
    }

    @Transactional
    public PolicyResponse renew(UUID policyId, String keycloakSubject) {
        Policy old = findOwnedPolicy(policyId, keycloakSubject);
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime newEff = old.getPolicyExpirationDate().isBefore(now) ? now : old.getPolicyExpirationDate();
        OffsetDateTime newExp = newEff.plus(365, ChronoUnit.DAYS);

        Policy renewed = new Policy();
        renewed.setPolicyId(UUID.randomUUID());
        renewed.setOrderId(old.getOrderId());
        renewed.setCustomerId(old.getCustomerId());
        renewed.setProductId(old.getProductId());
        renewed.setStatus(PolicyStatus.active);
        renewed.setPolicyEffectiveDate(newEff);
        renewed.setPolicyExpirationDate(newExp);
        renewed.setRenewalNumber(old.getRenewalNumber() + 1);
        renewed.setRenewal(true);
        renewed.setYearsSinceFirstPolicy(old.getYearsSinceFirstPolicy() + 1);
        renewed.setPolicyCountPrior(old.getPolicyCountPrior() + 1);
        renewed.setFinalPremiumVnd(old.getFinalPremiumVnd());
        renewed.setCreatedAt(now);
        policyRepository.save(renewed);

        enqueueEvent("PolicyRenewed", renewed.getPolicyId(), Map.of("customer_id", renewed.getCustomerId().toString(), "renewal_number", renewed.getRenewalNumber()));
        return toResponse(renewed);
    }

    @Transactional
    public PolicyResponse cancel(UUID policyId, CancelRequest request, String keycloakSubject) {
        Policy policy = findOwnedPolicy(policyId, keycloakSubject);
        if (policy.getStatus() != PolicyStatus.active) {
            throw new ServiceException(ErrorCode.POLICY_NOT_MODIFIABLE);
        }
        OffsetDateTime cancelDate = request.getCancelDate();
        if (cancelDate.isAfter(policy.getPolicyExpirationDate()) || cancelDate.isBefore(policy.getPolicyEffectiveDate())) {
            throw new ServiceException(ErrorCode.ENDORSEMENT_DATE_OUT_OF_RANGE);
        }
        policy.setStatus(PolicyStatus.cancelled);
        policy.setCancelDate(cancelDate);
        policyRepository.save(policy);

        long termDays = ChronoUnit.DAYS.between(policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate());
        long remainingDays = ChronoUnit.DAYS.between(cancelDate, policy.getPolicyExpirationDate());
        enqueueEvent("PolicyCancelled", policyId, Map.of("customer_id", policy.getCustomerId().toString(), "cancel_date", cancelDate.toString(), "final_premium_vnd", policy.getFinalPremiumVnd(), "remaining_days", remainingDays, "term_days", termDays));
        return toResponse(policy);
    }

    private Policy findOwnedPolicy(UUID policyId, String keycloakSubject) {
        Policy policy = policyRepository.findById(policyId)
                .orElseThrow(() -> new ServiceException(ErrorCode.FORBIDDEN_RESOURCE));
        UUID customerId = CustomerId.fromSubject(keycloakSubject);
        if (!policy.getCustomerId().equals(customerId)) {
            throw new ServiceException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        return policy;
    }

    private void enqueueEvent(String type, UUID policyId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policy_id", policyId.toString());
        payload.putAll(extra);
        try {
            outboxPublisher.enqueue(type, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue " + type, e);
        }
    }

    public PolicyResponse toResponse(Policy policy) {
        PolicyResponse resp = new PolicyResponse();
        resp.setPolicyId(policy.getPolicyId());
        resp.setOrderId(policy.getOrderId());
        resp.setCustomerId(policy.getCustomerId());
        resp.setProductId(policy.getProductId());
        resp.setStatus(policy.getStatus());
        resp.setPolicyEffectiveDate(policy.getPolicyEffectiveDate());
        resp.setPolicyExpirationDate(policy.getPolicyExpirationDate());
        resp.setRenewalNumber(policy.getRenewalNumber());
        resp.setRenewal(policy.isRenewal());
        resp.setFinalPremiumVnd(policy.getFinalPremiumVnd());
        resp.setCancelDate(policy.getCancelDate());
        resp.setCreatedAt(policy.getCreatedAt());
        return resp;
    }
}
