package dpp.order.service;

import dpp.order.entity.Policy;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the standardised {@code policy_document.content} JSON schema shared by
 * issuance (version 1) and endorsement (version 2+) so the FE parses a single
 * contract regardless of document version.
 */
public final class PolicyDocumentContentBuilder {

    private PolicyDocumentContentBuilder() {}

    /**
     * Build the canonical content map for a policy document.
     *
     * @param version            document version (1 for issuance, 2+ for endorsement)
     * @param policy             the policy entity (provides policy_id, customer_id, product_id,
     *                           status, effective/expiration dates, final_premium_vnd, asset_key)
     * @param line               product line (e.g. health, motorbike) — resolved by caller
     *                           via {@code resolveLineFromProductId} for backward compatibility
     * @param coverageAmountVnd  coverage amount in VND
     * @param deductibleVnd      deductible amount in VND
     * @param change             endorsement diff map (null for issuance)
     * @param issuedAt           timestamp when this document was issued
     * @return an ordered map ready to be serialised to JSON
     */
    public static Map<String, Object> build(int version, Policy policy, String line,
                                             long coverageAmountVnd, long deductibleVnd,
                                             Map<String, Object> change, OffsetDateTime issuedAt) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("document_type", "POLICY_CERTIFICATE");
        content.put("version", version);
        content.put("policy_id", policy.getPolicyId().toString());
        content.put("customer_id", policy.getCustomerId().toString());
        content.put("product_id", policy.getProductId());
        content.put("line", line);
        content.put("status", policy.getStatus().name());
        content.put("effective_date", policy.getPolicyEffectiveDate().toString());
        content.put("expiration_date", policy.getPolicyExpirationDate().toString());
        content.put("coverage_amount_vnd", coverageAmountVnd);
        content.put("deductible_vnd", deductibleVnd);
        content.put("final_premium_vnd", policy.getFinalPremiumVnd());
        content.put("asset_key", policy.getAssetKey());
        content.put("change", change);
        content.put("issued_at", issuedAt.toString());
        return content;
    }
}
