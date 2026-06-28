package dpp.billing.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class CreditApplicationView {
    private UUID appliedToInvoiceId;
    private UUID appliedToPolicyId;
    private long amountAppliedVnd;
    private OffsetDateTime createdAt;
}
