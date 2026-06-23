package dpp.product.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "rate_version")
public class RateVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "rate_version_id")
    private UUID rateVersionId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(name = "created_by", length = 100, nullable = false)
    private String createdBy;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private Boolean isCurrent = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public void setIsCurrent(Boolean isCurrent) {
        this.isCurrent = isCurrent;
    }
}


