package dpp.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class FnolRequest {
    @NotNull
    private UUID policyId;
    @NotNull
    private OffsetDateTime occurrenceDate;
    @NotBlank
    private String lossType;
    @NotBlank
    private String severityLevel;
}
