package dpp.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferenceDataVersionResponse<T> {
    private UUID versionId;
    private String referenceType;
    private String status;
    private Instant effectiveFrom;
    private String createdBy;
    private String approvedBy;
    private String changeReason;
    private String checksum;
    private Instant createdAt;
    private Instant activatedAt;
    private List<T> rows;
}
