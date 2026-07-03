package dpp.product.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "cost_index_version")
public class CostIndexVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "version_id")
    private UUID versionId;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;
    @Column(name = "approved_by", length = 100)
    private String approvedBy;
    @Column(name = "change_reason", nullable = false, length = 500)
    private String changeReason;
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "activated_at")
    private Instant activatedAt;
}
