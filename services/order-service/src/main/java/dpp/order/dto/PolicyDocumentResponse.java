package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Response view of a policy document (no JPA entity leak). */
@Getter
@Setter
public class PolicyDocumentResponse {
    private UUID documentId;
    private UUID policyId;
    private int version;
    private String content;
    private OffsetDateTime createdAt;
}
