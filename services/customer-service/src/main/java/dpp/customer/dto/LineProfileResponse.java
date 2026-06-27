package dpp.customer.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
public class LineProfileResponse {
    private UUID versionId;
    private String line;
    private Map<String, Object> lineAttributes;
    private OffsetDateTime effectiveAt;
}
