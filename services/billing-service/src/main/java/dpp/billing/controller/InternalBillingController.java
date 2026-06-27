package dpp.billing.controller;

import dpp.billing.dto.CreateInvoiceRequest;
import dpp.billing.dto.InvoiceResponse;
import dpp.billing.service.BillingService;
import dpp.billing.service.CreditService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoints for peer-service calls (not exposed via Kong).
 */
@RestController
@RequestMapping("/internal")
public class InternalBillingController {

    private final BillingService billingService;
    private final CreditService creditService;

    public InternalBillingController(BillingService billingService, CreditService creditService) {
        this.billingService = billingService;
        this.creditService = creditService;
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse createInvoice(@Valid @RequestBody CreateInvoiceRequest request) {
        return billingService.createInvoice(request);
    }

    @PostMapping("/invoices/void-by-endorsement")
    public ResponseEntity<Void> voidByEndorsement(@RequestParam("endorsement_request_id") UUID endorsementRequestId) {
        billingService.voidInvoiceByEndorsementRequestId(endorsementRequestId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/credits/apply-and-quote")
    public Map<String, Object> applyCreditAndQuote(@RequestParam("customer_id") UUID customerId,
                                                    @RequestParam("amount_vnd") long amountVnd) {
        long netDue = creditService.applyCreditsToQuote(customerId, amountVnd);
        long creditApplied = amountVnd - netDue;
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("credit_applied_vnd", creditApplied);
        resp.put("net_due_vnd", netDue);
        return resp;
    }
}
