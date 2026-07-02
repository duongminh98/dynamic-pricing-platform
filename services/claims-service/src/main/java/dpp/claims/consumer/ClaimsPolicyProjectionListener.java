package dpp.claims.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.claims.entity.ClaimExposureSegmentProjection;
import dpp.claims.entity.ClaimPolicyProjection;
import dpp.claims.repository.ClaimExposureSegmentProjectionRepository;
import dpp.claims.repository.ClaimPolicyProjectionRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Component
public class ClaimsPolicyProjectionListener {

    private final ClaimPolicyProjectionRepository policyProjectionRepository;
    private final ClaimExposureSegmentProjectionRepository segmentProjectionRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaimsPolicyProjectionListener(ClaimPolicyProjectionRepository policyProjectionRepository,
                                          ClaimExposureSegmentProjectionRepository segmentProjectionRepository) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.segmentProjectionRepository = segmentProjectionRepository;
    }

    @RabbitListener(queues = "claims.policy.issued.queue")
    public void onPolicyIssued(@Payload String message) {
        upsertPolicyAndSegment(message, true);
    }

    @RabbitListener(queues = "claims.policy.renewed.queue")
    public void onPolicyRenewed(@Payload String message) {
        upsertPolicyAndSegment(message, true);
    }

    @RabbitListener(queues = "claims.endorsement.applied.queue")
    public void onEndorsementApplied(@Payload String message) {
        upsertPolicyAndSegment(message, true);
    }

    @RabbitListener(queues = "claims.policy.cancelled.queue")
    public void onPolicyCancelled(@Payload String message) {
        upsertPolicyAndSegment(message, false);
    }

    @Transactional
    public void upsertPolicyAndSegment(String message, boolean includeSegment) {
        try {
            JsonNode node = objectMapper.readTree(message);
            UUID policyId = uuid(node, "policy_id");
            UUID customerId = uuid(node, "customer_id");
            if (policyId == null || customerId == null) {
                return;
            }
            ClaimPolicyProjection policy = policyProjectionRepository.findById(policyId)
                    .orElseGet(ClaimPolicyProjection::new);
            policy.setPolicyId(policyId);
            policy.setCustomerId(customerId);
            setIfPresent(node, "order_id", value -> policy.setOrderId(UUID.fromString(value)));
            setIfPresent(node, "quote_id", value -> policy.setQuoteId(UUID.fromString(value)));
            setIfPresent(node, "product_id", policy::setProductId);
            setIfPresent(node, "line", policy::setLine);
            if (text(node, "status") != null) {
                policy.setStatus(text(node, "status"));
            }
            setIfPresent(node, "policy_effective_date", value -> policy.setPolicyEffectiveDate(OffsetDateTime.parse(value)));
            setIfPresent(node, "policy_expiration_date", value -> policy.setPolicyExpirationDate(OffsetDateTime.parse(value)));
            setIfPresent(node, "new_effective_date", value -> policy.setPolicyEffectiveDate(OffsetDateTime.parse(value)));
            setIfPresent(node, "new_expiration_date", value -> policy.setPolicyExpirationDate(OffsetDateTime.parse(value)));
            if (node.has("final_premium_vnd") && !node.get("final_premium_vnd").isNull()) {
                policy.setFinalPremiumVnd(node.get("final_premium_vnd").asLong());
            }
            policy.setUpdatedAt(OffsetDateTime.now());
            policyProjectionRepository.save(policy);

            if (includeSegment && node.has("exposure_segment_seq") && node.has("segment_start") && node.has("segment_end")) {
                upsertSegment(policyId, node);
            }
        } catch (Exception e) {
            throw new RuntimeException("Claims policy projection failed", e);
        }
    }

    private void upsertSegment(UUID policyId, JsonNode node) {
        int seq = node.get("exposure_segment_seq").asInt();
        UUID segmentId = uuid(node, "exposure_id");
        if (segmentId == null) {
            segmentId = UUID.nameUUIDFromBytes((policyId + ":" + seq).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        ClaimExposureSegmentProjection segment = segmentProjectionRepository.findByPolicyIdAndExposureSegmentSeq(policyId, seq)
                .orElseGet(ClaimExposureSegmentProjection::new);
        segment.setSegmentId(segmentId);
        segment.setPolicyId(policyId);
        segment.setExposureSegmentSeq(seq);
        segment.setSegmentStart(OffsetDateTime.parse(text(node, "segment_start")));
        segment.setSegmentEnd(OffsetDateTime.parse(text(node, "segment_end")));
        segment.setEarnedExposureYears(node.has("earned_exposure_years") ? node.get("earned_exposure_years").asDouble() : 0.0);
        segment.setCoverageAmountVnd(node.has("coverage_amount_vnd") ? node.get("coverage_amount_vnd").asLong() : 0L);
        segment.setDeductibleVnd(node.has("deductible_vnd") ? node.get("deductible_vnd").asLong() : 0L);
        segment.setUpdatedAt(OffsetDateTime.now());
        segmentProjectionRepository.save(segment);
    }

    private UUID uuid(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private void setIfPresent(JsonNode node, String field, java.util.function.Consumer<String> consumer) {
        String value = text(node, field);
        if (value != null && !value.isBlank()) {
            consumer.accept(value);
        }
    }
}
