package dpp.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dpp.order.entity.Policy;
import dpp.order.entity.PolicyStatus;
import dpp.order.service.PolicyDocumentContentBuilder;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PolicyDocumentContentSchemaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Policy samplePolicy() {
        Policy p = new Policy();
        p.setPolicyId(UUID.randomUUID());
        p.setOrderId(UUID.randomUUID());
        p.setCustomerId(UUID.randomUUID());
        p.setProductId("HEALTH_BASIC");
        p.setStatus(PolicyStatus.active);
        p.setPolicyEffectiveDate(OffsetDateTime.now());
        p.setPolicyExpirationDate(OffsetDateTime.now().plusDays(365));
        p.setRenewalNumber(0);
        p.setRenewal(false);
        p.setYearsSinceFirstPolicy(0);
        p.setPolicyCountPrior(0);
        p.setFinalPremiumVnd(2_980_000L);
        p.setAssetKey("asset-123");
        p.setCreatedAt(OffsetDateTime.now());
        return p;
    }

    private JsonNode buildAndParse(int version, Map<String, Object> change) throws Exception {
        Policy policy = samplePolicy();
        OffsetDateTime issuedAt = OffsetDateTime.now();
        Map<String, Object> content = PolicyDocumentContentBuilder.build(
                version, policy, "health",
                500_000_000L, 1_000_000L, change, issuedAt);
        return mapper.readTree(mapper.writeValueAsString(content));
    }

    @Test
    void issuanceDocumentHasStandardSchema() throws Exception {
        JsonNode n = buildAndParse(1, null);

        assertEquals("POLICY_CERTIFICATE", n.get("document_type").asText());
        assertEquals(1, n.get("version").asInt());
        assertEquals("HEALTH_BASIC", n.get("product_id").asText());
        assertEquals("health", n.get("line").asText());
        assertEquals("active", n.get("status").asText());
        assertEquals(500_000_000L, n.get("coverage_amount_vnd").asLong());
        assertEquals(1_000_000L, n.get("deductible_vnd").asLong());
        assertEquals(2_980_000L, n.get("final_premium_vnd").asLong());
        assertEquals("asset-123", n.get("asset_key").asText());
        assertTrue(n.get("change").isNull());
        assertNotNull(n.get("issued_at").asText());
        assertNotNull(n.get("effective_date").asText());
        assertNotNull(n.get("expiration_date").asText());
        assertNotNull(n.get("policy_id").asText());
        assertNotNull(n.get("customer_id").asText());
    }

    @Test
    void endorsementDocumentHasVersion2AndStructuredChange() throws Exception {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("coverage_amount_vnd", Map.of("old", 300_000_000L, "new", 500_000_000L));
        change.put("deductible_vnd", Map.of("old", 500_000L, "new", 1_000_000L));
        change.put("premium", Map.of("old", 2_500_000L, "new", 2_980_000L));

        JsonNode n = buildAndParse(2, change);

        assertEquals(2, n.get("version").asInt());
        assertFalse(n.get("change").isNull());

        JsonNode ch = n.get("change");
        assertEquals(300_000_000L, ch.get("coverage_amount_vnd").get("old").asLong());
        assertEquals(500_000_000L, ch.get("coverage_amount_vnd").get("new").asLong());
        assertEquals(500_000L, ch.get("deductible_vnd").get("old").asLong());
        assertEquals(1_000_000L, ch.get("deductible_vnd").get("new").asLong());
        assertEquals(2_500_000L, ch.get("premium").get("old").asLong());
        assertEquals(2_980_000L, ch.get("premium").get("new").asLong());
    }

    @Test
    void bothVersionsShareSameKeySet() throws Exception {
        JsonNode v1 = buildAndParse(1, null);
        Map<String, Object> change = Map.of("premium", Map.of("old", 100L, "new", 200L));
        JsonNode v2 = buildAndParse(2, change);

        var v1Fields = mapper.convertValue(v1, Map.class).keySet();
        var v2Fields = mapper.convertValue(v2, Map.class).keySet();

        assertEquals(v1Fields, v2Fields, "Both versions must have the same key set");
    }
}
