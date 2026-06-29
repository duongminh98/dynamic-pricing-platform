package dpp.order.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class CancelRequest {
    private OffsetDateTime cancelDate;
    private String reason;
}
