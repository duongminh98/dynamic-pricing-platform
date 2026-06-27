package dpp.order.service;

import dpp.common.api.ErrorCode;
import dpp.common.api.ServiceException;
import dpp.common.outbox.OutboxPublisher;
import dpp.order.entity.*;
import dpp.order.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PolicyIssuanceService {

    private static final String CONSUMER = "order.invoice-paid";

    private final OrderRepository orderRepository;
    private final PolicyRepository policyRepository;
    private final ExposureSegmentRepository segmentRepository;
    private final PolicyDocumentRepository documentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxPublisher outboxPublisher;
    private final ObjectMapper objectMapper;

    public PolicyIssuanceService(OrderRepository orderRepository, PolicyRepository policyRepository,
                                  ExposureSegmentRepository segmentRepository, PolicyDocumentRepository documentRepository,
                                  ProcessedEventRepository processedEventRepository,
                                  OutboxPublisher outboxPublisher) {
        this.orderRepository = orderRepository;
        this.policyRepository = policyRepository;
        this.segmentRepository = segmentRepository;
        this.documentRepository = documentRepository;
        this.processedEventRepository = processedEventRepository;
        this.outboxPublisher = outboxPublisher;
        this.objectMapper = new ObjectMapper();
    }

    @Transactional
    public void issuePolicy(String eventId, UUID orderId, UUID policyIdFromInvoice) {
        // R6.6: idempotency on event_id; a redelivered InvoicePaid is a no-op.
        if (eventId != null && processedEventRepository.existsById(eventId)) {
            return;
        }

        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ServiceException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found for policy issuance", null));

        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return;
        }

        if (eventId != null) {
            ProcessedEvent pe = new ProcessedEvent();
            pe.setEventId(eventId);
            pe.setConsumer(CONSUMER);
            pe.setProcessedAt(OffsetDateTime.now());
            processedEventRepository.save(pe);
        }

        OffsetDateTime now = OffsetDateTime.now();
        // R22.3 / R34.1: travel policies run for trip_duration_days; other lines use a 1-year term.
        long termDays = 365L;
        if ("travel".equals(order.getLine()) && order.getTripDurationDays() != null
                && order.getTripDurationDays() > 0) {
            termDays = order.getTripDurationDays();
        }
        OffsetDateTime expiration = now.plus(termDays, ChronoUnit.DAYS);

        Policy policy = new Policy();
        UUID policyId = UUID.randomUUID();
        policy.setPolicyId(policyId);
        policy.setOrderId(orderId);
        policy.setCustomerId(order.getCustomerId());
        policy.setProductId(order.getProductId());
        policy.setStatus(PolicyStatus.active);
        policy.setPolicyEffectiveDate(now);
        policy.setPolicyExpirationDate(expiration);
        policy.setRenewalNumber(0);
        policy.setRenewal(false);
        policy.setYearsSinceFirstPolicy(0);
        policy.setPolicyCountPrior(0);
        policy.setFinalPremiumVnd(order.getFinalPremiumVnd());
        policy.setAssetKey(extractAssetKey(order));
        policy.setLine(order.getLine());
        policy.setCreatedAt(now);
        policyRepository.save(policy);

        ExposureSegment segment = new ExposureSegment();
        segment.setSegmentId(UUID.randomUUID());
        segment.setPolicyId(policyId);
        segment.setExposureSegmentSeq(0);
        segment.setSegmentStart(now);
        segment.setSegmentEnd(expiration);
        long days = Math.max(1, ChronoUnit.DAYS.between(now, expiration));
        segment.setEarnedExposureYears(days / 365.25);
        segment.setCoverageAmountVnd(order.getCoverageAmountVnd() != null ? order.getCoverageAmountVnd() : 0);
        segment.setDeductibleVnd(order.getDeductibleVnd() != null ? order.getDeductibleVnd() : 0);
        // Stamp the first segment with the full risk profile that was priced so an
        // endorsement can merge its change set onto a complete base and re-rate the
        // remaining term against the full feature set (R23.2/R23.8).
        String riskProfile = order.getRiskProfile();
        segment.setRiskSnapshot(riskProfile != null && !riskProfile.isBlank() ? riskProfile : "{}");
        segmentRepository.save(segment);

        PolicyDocument doc = new PolicyDocument();
        doc.setDocumentId(UUID.randomUUID());
        doc.setPolicyId(policyId);
        doc.setVersion(1);
        long coverageAmount = order.getCoverageAmountVnd() != null ? order.getCoverageAmountVnd() : 0;
        long deductible = order.getDeductibleVnd() != null ? order.getDeductibleVnd() : 0;
        Map<String, Object> content = PolicyDocumentContentBuilder.build(
                1, policy, order.getLine(),
                coverageAmount, deductible, null, now);
        try {
            doc.setContent(objectMapper.writeValueAsString(content));
        } catch (Exception e) {
            doc.setContent("{}");
        }
        doc.setCreatedAt(now);
        documentRepository.save(doc);

        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policy_id", policyId.toString());
        payload.put("customer_id", order.getCustomerId().toString());
        payload.put("product_id", order.getProductId());
        payload.put("final_premium_vnd", policy.getFinalPremiumVnd());
        payload.put("term_days", java.time.temporal.ChronoUnit.DAYS.between(
                policy.getPolicyEffectiveDate(), policy.getPolicyExpirationDate()));
        try {
            outboxPublisher.enqueue("PolicyIssued", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue PolicyIssued", e);
        }
    }

    /**
     * Extract asset key from order's risk profile for duplicate insurance tracking.
     */
    @SuppressWarnings("unchecked")
    private String extractAssetKey(OrderEntity order) {
        String line = order.getLine();
        if (line == null) {
            return null;
        }
        String riskProfile = order.getRiskProfile();
        if (riskProfile == null || riskProfile.isBlank()) {
            return null;
        }
        Map<String, Object> profile;
        try {
            profile = objectMapper.readValue(riskProfile, Map.class);
        } catch (Exception e) {
            return null;
        }
        return switch (line) {
            case "motorbike", "car" -> {
                Object plate = profile.get("vehicle_plate");
                yield plate != null ? plate.toString() : null;
            }
            case "home" -> {
                Object addr = profile.get("property_address");
                yield addr != null ? addr.toString() : null;
            }
            case "health" -> order.getCustomerId().toString();
            case "travel" -> {
                Object country = profile.get("destination_country");
                Object startDate = profile.get("trip_start_date");
                Object endDate = profile.get("trip_end_date");
                if (country != null && startDate != null && endDate != null) {
                    yield country + "|" + startDate + "|" + endDate;
                }
                yield null;
            }
            default -> null;
        };
    }
}
