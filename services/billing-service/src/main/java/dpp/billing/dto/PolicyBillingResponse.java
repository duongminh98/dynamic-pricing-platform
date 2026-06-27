package dpp.billing.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PolicyBillingResponse {
    private List<InvoiceResponse> invoices;
    private List<AdjustmentResponse> adjustments;
    private List<CreditResponse> credits;
    private List<RefundResponse> refunds;
    private long balanceVnd;
}
