package dpp.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class CancelRequest {
    @NotNull
    private OffsetDateTime cancelDate;
}
