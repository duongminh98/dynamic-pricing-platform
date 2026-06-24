package dpp.billing.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Combined billing view for a policy: its invoices and pro-rata adjustments
 * (design 3.7, R33.5). All monetary amounts are VND integers (C-4).
 */
@Getter
@Setter
public class PolicyBillingResponse {
    private List<InvoiceResponse> invoices;
    private List<AdjustmentResponse> adjustments;
}
