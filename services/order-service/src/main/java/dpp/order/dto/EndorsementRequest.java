package dpp.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Map;

@Getter
@Setter
public class EndorsementRequest {
    @NotNull
    private Map<String, Object> change;
    @NotNull
    private OffsetDateTime effectiveDate;
}
