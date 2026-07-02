package dpp.claims.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "claim_policy_projection")
public class ClaimPolicyProjection {
    @Id
    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id", length = 50)
    private String productId;

    @Column(length = 20)
    private String line;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "policy_effective_date")
    private OffsetDateTime policyEffectiveDate;

    @Column(name = "policy_expiration_date")
    private OffsetDateTime policyExpirationDate;

    @Column(name = "final_premium_vnd")
    private long finalPremiumVnd;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID policyId) { this.policyId = policyId; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public UUID getQuoteId() { return quoteId; }
    public void setQuoteId(UUID quoteId) { this.quoteId = quoteId; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getLine() { return line; }
    public void setLine(String line) { this.line = line; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getPolicyEffectiveDate() { return policyEffectiveDate; }
    public void setPolicyEffectiveDate(OffsetDateTime policyEffectiveDate) { this.policyEffectiveDate = policyEffectiveDate; }
    public OffsetDateTime getPolicyExpirationDate() { return policyExpirationDate; }
    public void setPolicyExpirationDate(OffsetDateTime policyExpirationDate) { this.policyExpirationDate = policyExpirationDate; }
    public long getFinalPremiumVnd() { return finalPremiumVnd; }
    public void setFinalPremiumVnd(long finalPremiumVnd) { this.finalPremiumVnd = finalPremiumVnd; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
