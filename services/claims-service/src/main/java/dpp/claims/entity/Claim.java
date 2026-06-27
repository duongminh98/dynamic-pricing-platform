package dpp.claims.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "claim")
@Getter
@Setter
public class Claim {

    @Id
    private UUID claimId;

    @Column(name = "policy_id", nullable = false)
    private UUID policyId;

    @Column(name = "exposure_segment_seq", nullable = false)
    private int exposureSegmentSeq;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "occurrence_date", nullable = false)
    private OffsetDateTime occurrenceDate;

    @Column(name = "report_date", nullable = false)
    private OffsetDateTime reportDate;

    @Column(name = "loss_type", nullable = false, length = 50)
    private String lossType;

    @Column(name = "incurred_amount", nullable = false)
    private long incurredAmount;

    @Column(name = "paid_amount", nullable = false)
    private long paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_status", nullable = false, length = 10)
    private ClaimStatus claimStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "misrepresentation_sanction", length = 20)
    private MisrepresentationSanction misrepresentationSanction;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "estimated_cost")
    private Long estimatedCost;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachments", columnDefinition = "jsonb")
    private List<String> attachments;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "admin_note", length = 2000)
    private String adminNote;
}
