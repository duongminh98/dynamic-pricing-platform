package dpp.billing.controller;

import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.dto.PolicyBillingResponse;
import dpp.billing.service.BillingService;
import dpp.common.security.CustomerId;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/billing/invoices")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        return billingService.createInvoice(request);
    }

    @PostMapping("/{id}/pay")
    public InvoiceResponse payInvoice(@PathVariable UUID id) {
        return billingService.payInvoice(id);
    }

    @GetMapping
    public PolicyBillingResponse getPolicyBilling(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestParam("policy_id") UUID policyId) {
        UUID customerId = CustomerId.fromSubject(jwt.getSubject());
        return billingService.getPolicyBilling(policyId, customerId);
    }
}
