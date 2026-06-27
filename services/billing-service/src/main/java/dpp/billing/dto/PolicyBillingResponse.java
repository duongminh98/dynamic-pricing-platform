package dpp.billing.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Combined billing view for a policy: its invoices, pro-rata adjustments (audit),
 * premium credits, and balance (design 3.7, R33.5).
 */
@Getter
@Setter
public class PolicyBillingResponse {
    private List<InvoiceResponse> invoices;
    private List<AdjustmentResponse> adjustments;
    private List<CreditResponse> credits;
    private long balanceVnd;
}
